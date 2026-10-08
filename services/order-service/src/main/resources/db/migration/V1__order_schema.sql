CREATE TABLE customers (
    id          UUID PRIMARY KEY,
    merchant_id UUID         NOT NULL,
    name        VARCHAR(200),
    email       VARCHAR(254),
    phone       VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_customers_merchant_email ON customers (merchant_id, LOWER(email)) WHERE email IS NOT NULL;

CREATE TABLE orders (
    id                 UUID PRIMARY KEY,
    merchant_id        UUID         NOT NULL,
    merchant_order_ref VARCHAR(100),
    customer_id        UUID REFERENCES customers (id),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),   -- integer minor units (paise)
    currency           VARCHAR(3)   NOT NULL,
    status             VARCHAR(20)  NOT NULL,                             -- CREATED | PAID | CANCELLED | EXPIRED
    description        VARCHAR(500),
    metadata           TEXT         NOT NULL DEFAULT '{}',                -- JSON object of string -> string
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_orders_merchant_created ON orders (merchant_id, created_at DESC);
CREATE INDEX ix_orders_status ON orders (status);
CREATE UNIQUE INDEX ux_orders_merchant_ref ON orders (merchant_id, merchant_order_ref) WHERE merchant_order_ref IS NOT NULL;

CREATE TABLE order_items (
    id                UUID PRIMARY KEY,
    order_id          UUID         NOT NULL REFERENCES orders (id),
    name              VARCHAR(200) NOT NULL,
    quantity          INT          NOT NULL CHECK (quantity > 0),
    unit_amount_minor BIGINT       NOT NULL CHECK (unit_amount_minor >= 0)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);

CREATE TABLE idempotency_keys (
    merchant_id     UUID         NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idem_key)
);
