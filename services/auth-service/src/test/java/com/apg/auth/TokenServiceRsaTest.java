package com.apg.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.auth.config.AuthProperties;
import com.apg.auth.domain.Role;
import com.apg.auth.domain.UserAccount;
import com.apg.auth.service.TokenService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenServiceRsaTest {
    private static String privateKeyOf(KeyPair kp) { return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()); }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits);
        return g.generateKeyPair();
    }

    private static AuthProperties props(String secret, String privateKey) {
        return new AuthProperties(secret, privateKey, Duration.ofMinutes(15), Duration.ofDays(7), 5, Duration.ofMinutes(15));
    }

    private static UserAccount user() {
        return new UserAccount("rsa@example.com", "Rsa User", "hash", Set.of(Role.MERCHANT_ADMIN), Instant.now());
    }

    @Test void rs256TokensCarryAKeyIdAndVerifyOnlyWithThePublicKey() throws Exception {
        KeyPair kp = rsa(2048);
        TokenService svc = new TokenService(props("", privateKeyOf(kp)), Clock.systemUTC());
        String jwt = svc.issueAccessToken(user());

        var jws = Jwts.parser().verifyWith(kp.getPublic()).requireIssuer("apg-auth").build().parseSignedClaims(jwt);
        assertEquals("RS256", jws.getHeader().getAlgorithm());
        assertEquals(TokenService.thumbprint(kp.getPublic().getEncoded()), jws.getHeader().getKeyId(), "kid = public key thumbprint");
        assertNotNull(svc.verifyAndGetSubject(jwt));
        assertEquals(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()), svc.publicKeyBase64());
        assertFalse(jwt.contains("PRIVATE"));

        assertThrows(JwtException.class, () -> Jwts.parser().verifyWith(rsa(2048).getPublic()).build().parseSignedClaims(jwt), "another key must not verify");
        String tampered = jwt.substring(0, jwt.length() - 4) + (jwt.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThrows(JwtException.class, () -> svc.verifyAndGetSubject(tampered));
    }

    @Test void weakOrBrokenKeysAreRefusedAtStartup() throws Exception {
        assertThrows(IllegalStateException.class, () -> new TokenService(props("", privateKeyOf(rsa(1024))), Clock.systemUTC()), "1024-bit RSA is too weak");
        assertThrows(IllegalStateException.class, () -> new TokenService(props("", "bm90IGEga2V5"), Clock.systemUTC()));
        assertThrows(IllegalStateException.class, () -> props("", ""), "neither key nor secret");
        assertThrows(IllegalStateException.class, () -> props("short", ""), "secret shorter than 32 bytes");
    }

    @Test void hs256FallbackStillWorksAndAPrivateKeyWinsWhenBothAreSet() throws Exception {
        String secret = "x".repeat(40);
        TokenService hs = new TokenService(props(secret, ""), Clock.systemUTC());
        String hsJwt = hs.issueAccessToken(user());
        assertEquals("HS256", Jwts.parser().verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(hsJwt).getHeader().getAlgorithm());
        assertNull(hs.publicKeyBase64());

        KeyPair kp = rsa(2048);
        TokenService both = new TokenService(props(secret, privateKeyOf(kp)), Clock.systemUTC());
        assertEquals("RS256", Jwts.parser().verifyWith(kp.getPublic()).build().parseSignedClaims(both.issueAccessToken(user())).getHeader().getAlgorithm());
        assertTrue(((RSAPublicKey) java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(both.publicKeyBase64())))).getModulus().bitLength() >= 2048);
    }
}
