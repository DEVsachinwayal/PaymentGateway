package com.apg.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.LocatorAdapter;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Verifies dashboard access tokens. With {@code jwt-public-key} configured only RS256 tokens signed by one of those keys are
 * accepted (key chosen by the {@code kid} header = first 16 chars of base64url(SHA-256(X.509 DER)), so keys can be rotated by
 * listing old and new): the gateway cannot mint tokens, an HS256 token "signed" with the public key bytes (algorithm confusion)
 * or an unsigned token is rejected. Without a public key the HS256 secret is used (dev fallback).
 */
@Component
public class JwtVerifier {
    private static final String ISSUER = "apg-auth";

    private final Map<String, PublicKey> publicKeys = new LinkedHashMap<>();
    private final SecretKey hmacKey;

    public JwtVerifier(GatewayProperties props) {
        if (props.jwtPublicKey() != null && !props.jwtPublicKey().isBlank()) {
            for (String b64 : props.jwtPublicKey().split(",")) {
                if (b64.isBlank()) continue;
                try {
                    byte[] der = Base64.getDecoder().decode(b64.trim());
                    PublicKey k = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                    if (((RSAPublicKey) k).getModulus().bitLength() < 2048) throw new IllegalStateException("JWT_PUBLIC_KEY must be at least 2048 bits");
                    publicKeys.put(thumbprint(der), k);
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("JWT_PUBLIC_KEY is not a valid base64 X.509 RSA public key", e);
                }
            }
            this.hmacKey = null;
        } else {
            this.hmacKey = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        }
    }

    static String thumbprint(byte[] x509Der) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(x509Der)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean asymmetric() { return hmacKey == null; }

    /** @throws JwtException for anything that is not a valid, unexpired token from auth-service */
    public Claims verify(String token) {
        return Jwts.parser().keyLocator(new LocatorAdapter<Key>() {
            @Override
            protected Key locate(JwsHeader header) {
                if (hmacKey != null) return hmacKey;
                if (!"RS256".equals(header.getAlgorithm())) throw new UnsupportedJwtException("only RS256 is accepted");
                PublicKey k = header.getKeyId() == null ? null : publicKeys.get(header.getKeyId());
                if (k == null) throw new UnsupportedJwtException("unknown signing key");
                return k;
            }
        }).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
    }
}
