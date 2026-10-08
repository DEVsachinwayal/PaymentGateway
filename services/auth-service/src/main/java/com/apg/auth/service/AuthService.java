package com.apg.auth.service;

import com.apg.auth.config.AuthProperties;
import com.apg.auth.domain.LoginAudit;
import com.apg.auth.domain.RefreshSession;
import com.apg.auth.domain.Role;
import com.apg.auth.domain.UserAccount;
import com.apg.auth.repo.Repositories.LoginAuditRepository;
import com.apg.auth.repo.Repositories.RefreshSessionRepository;
import com.apg.auth.repo.Repositories.UserRepository;
import com.apg.auth.web.AuthDtos.RegisterRequest;
import com.apg.auth.web.AuthDtos.RegisterResponse;
import com.apg.auth.web.AuthDtos.TokenResponse;
import com.apg.common.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * noRollbackFor ApiException: failed-login counters, audit rows and refresh-token-reuse family
 * revocation must be committed even though the request ends with an error.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class AuthService {
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final LoginAuditRepository audit;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuthProperties props;
    private final Clock clock;
    /** Pre-computed hash so unknown-email logins cost the same as wrong-password logins. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshSessionRepository sessions, LoginAuditRepository audit,
                       PasswordEncoder encoder, TokenService tokens, AuthProperties props, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.encoder = encoder;
        this.tokens = tokens;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public RegisterResponse register(RegisterRequest req) {
        Instant now = clock.instant();
        if (users.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
        // Self-registration only ever yields a merchant admin; privileged roles are granted by SYSTEM_ADMIN later.
        UserAccount user = new UserAccount(req.email(), req.fullName(), encoder.encode(req.password()),
                Set.of(Role.MERCHANT_ADMIN), now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
        return new RegisterResponse(user.getId(), user.getEmail());
    }

    public TokenResponse login(String email, String password, String ip, String userAgent) {
        Instant now = clock.instant();
        UserAccount user = users.findByEmail(email).orElse(null);

        if (user == null) {
            encoder.matches(password, dummyHash);
            audit.save(new LoginAudit(null, email, false, "UNKNOWN_USER", ip, now));
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            audit.save(new LoginAudit(user.getId(), email, false, "LOCKED", ip, now));
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Account temporarily locked");
        }
        if (!user.isActive() || !encoder.matches(password, user.getPasswordHash())) {
            user.recordFailure(props.maxFailedAttempts(), props.lockDuration(), now);
            audit.save(new LoginAudit(user.getId(), email, false, "BAD_PASSWORD", ip, now));
            throw invalidCredentials();
        }
        user.recordSuccess(now);
        audit.save(new LoginAudit(user.getId(), email, true, null, ip, now));
        return issue(user, UUID.randomUUID(), now, ip, userAgent);
    }

    public TokenResponse refresh(String refreshToken, String ip, String userAgent) {
        Instant now = clock.instant();
        RefreshSession current = sessions.findByTokenHash(TokenService.hash(refreshToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid refresh token"));

        if (current.isRevoked()) {
            // A rotated token is being replayed: assume theft and kill the whole family.
            sessions.revokeFamily(current.getFamilyId(), now);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "Refresh token reuse detected; session revoked");
        }
        if (current.isExpired(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "Refresh token expired");
        }
        UserAccount user = users.findById(current.getUserId())
                .filter(UserAccount::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid refresh token"));

        TokenResponse next = issue(user, current.getFamilyId(), now, ip, userAgent);
        sessions.findByTokenHash(TokenService.hash(next.refreshToken()))
                .ifPresent(n -> current.revoke(now, n.getId()));
        return next;
    }

    public void logout(String refreshToken) {
        sessions.findByTokenHash(TokenService.hash(refreshToken))
                .ifPresent(s -> sessions.revokeFamily(s.getFamilyId(), clock.instant()));
    }

    private TokenResponse issue(UserAccount user, UUID familyId, Instant now, String ip, String userAgent) {
        String refresh = tokens.newRefreshToken();
        sessions.saveAndFlush(new RefreshSession(user.getId(), familyId, TokenService.hash(refresh),
                now.plus(props.refreshTokenTtl()), now, ip, userAgent));
        return new TokenResponse(tokens.issueAccessToken(user), refresh, "Bearer", tokens.accessTtlSeconds());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
