CREATE TABLE merchants (
    id            UUID PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    legal_name    VARCHAR(250),
    support_email VARCHAR(254) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SANDBOX',   -- SANDBOX | ACTIVE | SUSPENDED
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE merchant_users (
    id          UUID PRIMARY KEY,
    merchant_id UUID        NOT NULL REFERENCES merchants (id),
    user_id     UUID        NOT NULL,
    role        VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
-- one dashboard user belongs to at most one merchant (for now)
CREATE UNIQUE INDEX ux_merchant_users_user ON merchant_users (user_id);
CREATE INDEX ix_merchant_users_merchant ON merchant_users (merchant_id);

CREATE TABLE merchant_settings (
    merchant_id          UUID PRIMARY KEY REFERENCES merchants (id),
    default_currency     VARCHAR(3)     NOT NULL DEFAULT 'INR',
    allowed_return_urls  TEXT         NOT NULL DEFAULT '',   -- newline separated https URL prefixes
    updated_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE api_keys (
    id              UUID PRIMARY KEY,
    merchant_id     UUID         NOT NULL REFERENCES merchants (id),
    key_id          VARCHAR(40)  NOT NULL,        -- public part, e.g. sk_test_<16 hex>
    secret_hash     VARCHAR(64)  NOT NULL,        -- HMAC-SHA256(pepper, secret); secret is never stored
    label           VARCHAR(100) NOT NULL,
    mode            VARCHAR(8)   NOT NULL,        -- TEST | LIVE
    status          VARCHAR(10)  NOT NULL,        -- ACTIVE | REVOKED
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    rotated_from_id UUID
);
CREATE UNIQUE INDEX ux_api_keys_key_id ON api_keys (key_id);
CREATE INDEX ix_api_keys_merchant ON api_keys (merchant_id);

CREATE TABLE webhook_endpoints (
    id          UUID PRIMARY KEY,
    merchant_id UUID         NOT NULL REFERENCES merchants (id),
    url         VARCHAR(500) NOT NULL,
    events      VARCHAR(500) NOT NULL,            -- comma separated event types
    secret_enc  TEXT         NOT NULL,            -- AES-GCM encrypted signing secret
    status      VARCHAR(10)  NOT NULL,            -- ACTIVE | DELETED
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_webhook_endpoints_merchant ON webhook_endpoints (merchant_id);
