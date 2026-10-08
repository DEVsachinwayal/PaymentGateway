package com.apg.provider.payu;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.provider.api.ProviderModels.*;
import com.apg.provider.service.CallLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in contact with the REAL PayU sandbox: run with PAYU_LIVE_TEST=true and PAYU_MERCHANT_KEY/SALT/BASE_URL set.
 * Makes ONE harmless call (status of an unknown txn). Keep it opt-in: PayU rate-limits callers that send bad hashes.
 */
@EnabledIfEnvironmentVariable(named = "PAYU_LIVE_TEST", matches = "true")
class PayULiveSandboxTest {
    @Test void realSandboxAcceptsOurSignatureAndReportsUnknownTransactionAsNotFound() {
        PayUProvider p = new PayUProvider(new PayUProperties(true, 1, System.getenv("PAYU_MERCHANT_KEY"),
                System.getenv("PAYU_MERCHANT_SALT"), System.getenv("PAYU_BASE_URL"), Duration.ofSeconds(5), Duration.ofSeconds(15)),
                new ObjectMapper(), new CallLog(null, Clock.systemUTC()) {
                    @Override public void record(String p, String op, String r, String o, Integer h, long m, String d) { }
                }, Clock.systemUTC());
        PaymentStatusResponse r = p.getPaymentStatus("apgit" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        assertEquals(Status.NOT_FOUND, r.status());
    }
}
