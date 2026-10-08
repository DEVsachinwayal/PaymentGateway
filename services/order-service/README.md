# order-service (port 8083, DB `apg_order`)
Orders, customers, order items. Amount is an integer in minor units (49900 = INR 499.00); floats are rejected. Currencies allow-listed (`apg.order.allowed-currencies`, default INR).
- `POST /api/v1/orders` needs `Idempotency-Key` (replay returns the original order + `Idempotent-Replayed: true`; same key with another body => 422).
- `GET /api/v1/orders/{id}`, `GET /api/v1/orders?page&size` (scoped to the caller's merchant; other merchants get 404).
- Callers: merchant API key, or dashboard user with MERCHANT_ADMIN/MERCHANT_DEVELOPER. Status changes (PAID...) come from payment events in M5/M12.
Env: POSTGRES_USER, POSTGRES_PASSWORD, INTERNAL_SERVICE_TOKEN.
