CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email));

CREATE TABLE roles (
    name VARCHAR(40) PRIMARY KEY
);
INSERT INTO roles (name) VALUES
    ('MERCHANT_ADMIN'), ('MERCHANT_DEVELOPER'), ('APG_OPERATIONS'), ('APG_FINANCE'), ('SYSTEM_ADMIN');

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id),
    role    VARCHAR(40) NOT NULL REFERENCES roles (name),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_sessions (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    family_id    UUID        NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    created_at   TIMESTAMPTZ NOT NULL,
    ip           VARCHAR(64),
    user_agent   VARCHAR(300)
);
CREATE UNIQUE INDEX ux_refresh_token_hash ON refresh_sessions (token_hash);
CREATE INDEX ix_refresh_family ON refresh_sessions (family_id);
CREATE INDEX ix_refresh_user ON refresh_sessions (user_id);

CREATE TABLE login_audit (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID,
    email      VARCHAR(254),
    success    BOOLEAN     NOT NULL,
    reason     VARCHAR(60),
    ip         VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_login_audit_email ON login_audit (LOWER(email), created_at);
