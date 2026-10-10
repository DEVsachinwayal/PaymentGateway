package com.apg.gateway;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {
    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String b64(KeyPair kp) { return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()); }

    private static String kid(KeyPair kp) { return JwtVerifier.thumbprint(kp.getPublic().getEncoded()); }

    private static GatewayProperties props(String secret, String publicKeys) {
        return new GatewayProperties(secret, publicKeys, "internal-token-for-tests-123", "http://a", "http://m", "http://o", "http://p", "http://o2", "http://w", "http://r", "http://l", "http://s", "http://n", "http://pl",
                List.of("http://localhost:5173"), 1000, new GatewayProperties.RateLimit(10, 20));
    }

    private static String token(KeyPair signer, String kid, String issuer, Instant exp) {
        var b = Jwts.builder();
        if (kid != null) b.header().keyId(kid);
        return b.issuer(issuer).subject("user-1").claim("roles", List.of("MERCHANT_ADMIN")).expiration(Date.from(exp))
                .signWith(signer.getPrivate(), Jwts.SIG.RS256).compact();
    }

    @Test void validRs256TokenIsAccepted() throws Exception {
        KeyPair kp = rsa();
        JwtVerifier v = new JwtVerifier(props("", b64(kp)));
        assertTrue(v.asymmetric());
        assertEquals("user-1", v.verify(token(kp, kid(kp), "apg-auth", Instant.now().plusSeconds(60))).getSubject());
    }

    @Test void tokensThatMustBeRejected() throws Exception {
        KeyPair trusted = rsa(), attacker = rsa();
        JwtVerifier v = new JwtVerifier(props("", b64(trusted)));
        Instant ok = Instant.now().plusSeconds(60);

        assertThrows(JwtException.class, () -> v.verify(token(attacker, kid(trusted), "apg-auth", ok)), "signed by another key, claiming our kid");
        assertThrows(JwtException.class, () -> v.verify(token(attacker, kid(attacker), "apg-auth", ok)), "unknown kid");
        assertThrows(JwtException.class, () -> v.verify(token(trusted, null, "apg-auth", ok)), "no kid");
        assertThrows(JwtException.class, () -> v.verify(token(trusted, kid(trusted), "someone-else", ok)), "wrong issuer");
        assertThrows(JwtException.class, () -> v.verify(token(trusted, kid(trusted), "apg-auth", Instant.now().minusSeconds(5))), "expired");
        assertThrows(JwtException.class, () -> v.verify("not.a.jwt"));

        // algorithm confusion: an HS token "signed" with the PUBLIC key bytes must not be accepted by an RS256 gateway
        var hmac = Keys.hmacShaKeyFor(trusted.getPublic().getEncoded());
        String confused = Jwts.builder().header().keyId(kid(trusted)).and().issuer("apg-auth").subject("admin").expiration(Date.from(ok)).signWith(hmac).compact();
        assertThrows(JwtException.class, () -> v.verify(confused), "HS256 with the public key as secret");

        String unsigned = Jwts.builder().header().keyId(kid(trusted)).and().issuer("apg-auth").subject("admin").expiration(Date.from(ok)).compact();
        assertThrows(JwtException.class, () -> v.verify(unsigned), "alg=none");
    }

    @Test void keyRotationAcceptsOldAndNewKeysAtTheSameTime() throws Exception {
        KeyPair oldKey = rsa(), newKey = rsa();
        JwtVerifier v = new JwtVerifier(props("", b64(oldKey) + "," + b64(newKey)));
        Instant ok = Instant.now().plusSeconds(60);
        assertEquals("user-1", v.verify(token(oldKey, kid(oldKey), "apg-auth", ok)).getSubject());
        assertEquals("user-1", v.verify(token(newKey, kid(newKey), "apg-auth", ok)).getSubject());
        JwtVerifier onlyNew = new JwtVerifier(props("", b64(newKey)));
        assertThrows(JwtException.class, () -> onlyNew.verify(token(oldKey, kid(oldKey), "apg-auth", ok)), "retired key stops working");
    }

    @Test void whenAPublicKeyIsConfiguredTheSharedSecretIsIgnored() throws Exception {
        KeyPair kp = rsa();
        String secret = "s".repeat(40);
        JwtVerifier v = new JwtVerifier(props(secret, b64(kp)));
        String hs = Jwts.builder().issuer("apg-auth").subject("forged").expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertThrows(JwtException.class, () -> v.verify(hs), "a leaked HS secret must be useless once RS256 is on");
    }

    @Test void hs256FallbackStillWorksWithoutAPublicKey() throws Exception {
        String secret = "s".repeat(40);
        JwtVerifier v = new JwtVerifier(props(secret, ""));
        assertFalse(v.asymmetric());
        String hs = Jwts.builder().issuer("apg-auth").subject("dev-user").expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertEquals("dev-user", v.verify(hs).getSubject());
        KeyPair kp = rsa();
        assertThrows(JwtException.class, () -> v.verify(token(kp, kid(kp), "apg-auth", Instant.now().plusSeconds(60))), "RS256 token is not accepted in HS mode");
    }

    @Test void badConfigurationFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> props("", ""));
        assertThrows(IllegalStateException.class, () -> props("short", null));
        assertThrows(IllegalStateException.class, () -> new JwtVerifier(props("", "bm90IGEga2V5")));
    }
}
