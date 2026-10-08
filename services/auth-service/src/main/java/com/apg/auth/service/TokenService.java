package com.apg.auth.service;

import com.apg.auth.config.AuthProperties;
import com.apg.auth.domain.Role;
import com.apg.auth.domain.UserAccount;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

/** Issues HS256 access JWTs and opaque refresh tokens (only SHA-256 hashes are stored). */
@Component
public class TokenService {
    private static final String ISSUER = "apg-auth";
    private final SecretKey key;
    private final AuthProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UserAccount user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().map(Role::name).sorted().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl())))
                .signWith(key)
                .compact();
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
        return Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                .parseSignedClaims(jwt).getPayload().getSubject();
    }
}
