package com.apg.auth.service;

import com.apg.auth.config.AuthProperties;
import com.apg.auth.domain.Role;
import com.apg.auth.domain.UserAccount;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues access JWTs and opaque refresh tokens (only SHA-256 hashes are stored). Access tokens are RS256 when a private key is
 * configured (header carries {@code kid} = thumbprint of the public key so the gateway can pick the right key during rotation),
 * otherwise HS256 with the shared secret (dev fallback).
 */
@Component
public class TokenService {
    private static final String ISSUER = "apg-auth";
    private final SecretKey key;               // HS256 mode
    private final PrivateKey privateKey;       // RS256 mode
    private final PublicKey publicKey;
    private final String keyId;
    private final AuthProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        if (props.jwtPrivateKey() != null && !props.jwtPrivateKey().isBlank()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(props.jwtPrivateKey().trim())));
                RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
                if (crt.getModulus().bitLength() < 2048) throw new IllegalStateException("JWT_PRIVATE_KEY must be an RSA key of at least 2048 bits");
                this.publicKey = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
                this.keyId = thumbprint(publicKey.getEncoded());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("JWT_PRIVATE_KEY is not a valid base64 PKCS#8 RSA private key", e);
            }
            this.key = null;
        } else {
            this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
            this.privateKey = null;
            this.publicKey = null;
            this.keyId = null;
        }
    }

    /** Same formula the gateway uses to name its verification keys: first 16 chars of base64url(SHA-256(X.509 DER)). */
    public static String thumbprint(byte[] x509Der) {
        try {
            String full = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(x509Der));
            return full.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Base64 X.509 public key to configure on the gateway (JWT_PUBLIC_KEY); null in HS256 mode. */
    public String publicKeyBase64() { return publicKey == null ? null : Base64.getEncoder().encodeToString(publicKey.getEncoded()); }

    public String issueAccessToken(UserAccount user) {
        Instant now = clock.instant();
        var b = Jwts.builder();
        if (privateKey != null) b.header().keyId(keyId);
        b       .issuer(ISSUER)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().map(Role::name).sorted().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl())));
        return (privateKey != null ? b.signWith(privateKey, Jwts.SIG.RS256) : b.signWith(key)).compact();
    }

    public long accessTtlSeconds() { return props.accessTokenTtl().toSeconds(); }

    public String newRefreshToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Used by tests / future introspection; returns subject if token is valid. */
    public String verifyAndGetSubject(String jwt) {
        var parser = privateKey != null ? Jwts.parser().verifyWith(publicKey) : Jwts.parser().verifyWith(key);
        return parser.requireIssuer(ISSUER).build().parseSignedClaims(jwt).getPayload().getSubject();
    }
}
