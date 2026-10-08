package com.apg.auth.repo;

import com.apg.auth.domain.LoginAudit;
import com.apg.auth.domain.RefreshSession;
import com.apg.auth.domain.UserAccount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public final class Repositories {
    private Repositories() {}

    public interface UserRepository extends JpaRepository<UserAccount, UUID> {
        @Query("select u from UserAccount u where lower(u.email) = lower(?1)")
        Optional<UserAccount> findByEmail(String email);

        @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(?1)")
        boolean existsByEmail(String email);
    }

    public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
        Optional<RefreshSession> findByTokenHash(String tokenHash);

        List<RefreshSession> findByFamilyId(UUID familyId);

        @Modifying
        @Query("update RefreshSession s set s.revokedAt = ?2 where s.familyId = ?1 and s.revokedAt is null")
        int revokeFamily(UUID familyId, Instant now);
    }

    public interface LoginAuditRepository extends JpaRepository<LoginAudit, Long> {}
}
