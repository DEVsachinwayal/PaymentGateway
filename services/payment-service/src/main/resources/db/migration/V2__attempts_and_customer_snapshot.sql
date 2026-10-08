-- customer/product snapshot taken from the order at payment creation (needed by providers such as PayU)
ALTER TABLE payment_intents
    ADD COLUMN description    VARCHAR(500),
    ADD COLUMN customer_name  VARCHAR(200),
    ADD COLUMN customer_email VARCHAR(254),
    ADD COLUMN customer_phone VARCHAR(20);

-- every provider attempt gets its own unique reference (sent to the provider as txnid)
ALTER TABLE payment_attempts ADD COLUMN provider_reference VARCHAR(40);
CREATE UNIQUE INDEX ux_attempts_reference ON payment_attempts (provider_reference);
CREATE INDEX ix_attempts_payment ON payment_attempts (payment_id, attempt_no DESC);
