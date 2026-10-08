package com.apg.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    private String email;
    private String fullName;
    private String passwordHash;
    private String status = "ACTIVE";
    private int failedAttempts;
    private Instant lockedUntil;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    protected UserAccount() {}

    public UserAccount(String email, String fullName, String passwordHash, Set<Role> roles, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email.trim().toLowerCase();
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.roles = EnumSet.copyOf(roles);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public boolean isActive() { return "ACTIVE".equals(status); }

    public void recordFailure(int max, java.time.Duration lock, Instant now) {
        failedAttempts++;
        if (failedAttempts >= max) {
            lockedUntil = now.plus(lock);
            failedAttempts = 0;
        }
        updatedAt = now;
    }

    public void recordSuccess(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public Set<Role> getRoles() { return roles; }
}
