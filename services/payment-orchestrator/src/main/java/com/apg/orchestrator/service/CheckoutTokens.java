package com.apg.orchestrator.service;

import com.apg.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Stateless signed token that lets the customer's browser open the hosted redirect page:
 * {@code base64url(attemptReference).expiryEpochSeconds.hmacSha256}. It grants no API access, only this one page.
 */
@Component
public class CheckoutTokens {
    private final SecretKeySpec key;
    private final Clock clock;
    private final OrchestratorProperties props;

    public CheckoutTokens(OrchestratorProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = new SecretKeySpec(props.checkoutTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public record Issued(String token, Instant expiresAt) {}

    public Issued issue(String attemptReference) {
        Instant exp = clock.instant().plus(props.checkoutTokenTtl());
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(attemptReference.getBytes(StandardCharsets.UTF_8))
                + "." + exp.getEpochSecond();
        return new Issued(body + "." + sign(body), exp);
    }

    /** @return the attempt reference. 404 for forged tokens, 410 for expired ones. */
    public String verify(String token) {
        String[] p = token == null ? new String[0] : token.split("\\.");
        if (p.length != 3) throw notFound();
        String body = p[0] + "." + p[1];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), p[2].getBytes(StandardCharsets.UTF_8))) throw notFound();
        long exp;
        try {
            exp = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            throw notFound();
        }
        if (clock.instant().getEpochSecond() > exp) {
            throw new ApiException(HttpStatus.GONE, "CHECKOUT_EXPIRED", "This checkout link has expired");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CHECKOUT_NOT_FOUND", "Checkout link not found");
    }
}
