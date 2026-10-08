package com.apg.orchestrator.service;

import com.apg.common.api.ApiException;
import com.apg.common.web.CorrelationIdFilter;
import com.apg.orchestrator.client.PaymentClient;
import com.apg.orchestrator.client.PaymentClient.Attempt;
import com.apg.orchestrator.client.PaymentClient.Payment;
import com.apg.orchestrator.client.ProviderClient;
import com.apg.orchestrator.client.ProviderClient.ProviderStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves what really happened to an attempt by asking the provider (never by trusting a browser or an
 * unverified callback) and applies the outcome through the payment state machine. Idempotent: calling it
 * again for a settled payment changes nothing.
 *
 * Rules (spec 7): a timeout/provider error NEVER fails a payment - it becomes VERIFY_REQUIRED; FAILED needs an
 * explicit provider answer (or proof that the provider never saw the transaction after a long grace period).
 */
@Service
public class VerificationService {
    public enum Result { SETTLED_SUCCESS, SETTLED_FAILED, STILL_PENDING, MARKED_UNKNOWN, AMOUNT_MISMATCH, NO_CHANGE }

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final Set<String> SETTLED = Set.of("SUCCESS", "FAILED", "CAPTURED", "REFUND_PENDING", "REFUNDED", "PARTIALLY_REFUNDED", "AUTHORIZED");

    private final PaymentClient payments;
    private final ProviderClient providers;
    private final JdbcTemplate jdbc;
    private final OrchestratorProperties props;
    private final Clock clock;

    public VerificationService(PaymentClient payments, ProviderClient providers, JdbcTemplate jdbc,
                               OrchestratorProperties props, Clock clock) {
        this.payments = payments;
        this.providers = providers;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** @param trigger WEBHOOK or VERIFICATION (also used as the state-history source) */
    public Result verify(String reference, String trigger) {
        Attempt attempt = payments.attempt(reference);
        Payment p = payments.payment(attempt.paymentId());
        if (SETTLED.contains(p.status())) {
            log(reference, p, trigger, null, Result.NO_CHANGE, "payment already " + p.status());
            return Result.NO_CHANGE;
        }

        ProviderStatus ps;
        try {
            ps = providers.status(attempt.provider(), reference);
        } catch (ApiException e) {
            // Provider unreachable / rate limited / garbled: outcome UNKNOWN, never FAILED.
            markUnknown(p, "provider status unavailable: " + e.code());
            log(reference, p, trigger, null, Result.MARKED_UNKNOWN, e.code());
            return Result.MARKED_UNKNOWN;
        }

        Duration age = Duration.between(p.updatedAt(), clock.instant());
        switch (ps.status()) {
            case "SUCCESS" -> {
                if (ps.amountMinor() == null || ps.amountMinor() != p.amount()) {
                    markUnknown(p, "amount mismatch: provider=" + ps.amountMinor() + " expected=" + p.amount());
                    payments.updateAttempt(reference, "UNKNOWN", ps.providerPaymentId(), "AMOUNT_MISMATCH");
                    log(reference, p, trigger, ps.status(), Result.AMOUNT_MISMATCH, "provider=" + ps.amountMinor() + " expected=" + p.amount());
                    return Result.AMOUNT_MISMATCH;
                }
                payments.updateAttempt(reference, "SUCCESS", ps.providerPaymentId(), null);
                if ("PENDING".equals(p.status())) payments.transition(p.paymentId(), "PROCESSING", "provider reports payment", trigger);
                payments.transition(p.paymentId(), "SUCCESS", "provider confirmed capture " + safe(ps.providerPaymentId()), trigger);
                log(reference, p, trigger, ps.status(), Result.SETTLED_SUCCESS, null);
                return Result.SETTLED_SUCCESS;
            }
            case "FAILED" -> {
                payments.updateAttempt(reference, "FAILED", ps.providerPaymentId(), ps.errorCode());
                payments.transition(p.paymentId(), "FAILED", "provider reports failure " + safe(ps.errorCode()), trigger);
                log(reference, p, trigger, ps.status(), Result.SETTLED_FAILED, ps.errorMessage());
                return Result.SETTLED_FAILED;
            }
            case "NOT_FOUND" -> {
                if (age.compareTo(props.verification().abandonAfter()) > 0 && !"VERIFY_REQUIRED".equals(p.status())) {
                    payments.updateAttempt(reference, "FAILED", null, "CUSTOMER_NEVER_ARRIVED");
                    payments.transition(p.paymentId(), "FAILED", "provider never received this transaction", trigger);
                    log(reference, p, trigger, ps.status(), Result.SETTLED_FAILED, "abandoned");
                    return Result.SETTLED_FAILED;
                }
                log(reference, p, trigger, ps.status(), Result.STILL_PENDING, "not found yet (customer may still be on the way)");
                return Result.STILL_PENDING;
            }
            default -> {   // PENDING / UNKNOWN
                if ("PROCESSING".equals(p.status()) && age.compareTo(props.verification().processingTimeout()) > 0) {
                    markUnknown(p, "provider still pending after " + props.verification().processingTimeout());
                    log(reference, p, trigger, ps.status(), Result.MARKED_UNKNOWN, "processing timeout");
                    return Result.MARKED_UNKNOWN;
                }
                log(reference, p, trigger, ps.status(), Result.STILL_PENDING, null);
                return Result.STILL_PENDING;
            }
        }
    }

    private void markUnknown(Payment p, String reason) {
        if ("VERIFY_REQUIRED".equals(p.status())) return;
        if ("CREATED".equals(p.status())) return;
        try {
            payments.transition(p.paymentId(), "VERIFY_REQUIRED", reason, "VERIFICATION");
        } catch (ApiException e) {
            log.warn("could not move payment {} to VERIFY_REQUIRED: {}", p.paymentId(), e.code());
        }
    }

    private void log(String ref, Payment p, String trigger, String providerStatus, Result r, String detail) {
        jdbc.update("INSERT INTO verification_log (attempt_reference, payment_id, trigger_source, provider_status, result, detail, correlation_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                ref, p.paymentId(), trigger, providerStatus, r.name(),
                detail == null ? null : detail.substring(0, Math.min(300, detail.length())),
                MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(Instant.now(clock)));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
