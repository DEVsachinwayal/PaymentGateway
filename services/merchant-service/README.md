# merchant-service (port 8082, DB `apg_merchant`)
Merchant profile + settings, API keys, webhook endpoints. Reachable only through the gateway: every request needs `X-Internal-Token` (INTERNAL_SERVICE_TOKEN), identity comes from gateway headers (`X-User-Id`, `X-User-Roles`, `X-Auth-Type`).
- API key format `sk_test_<16hex>.<secret>`; only HMAC-SHA256(pepper, secret) stored; secret returned once (create/rotate).
- Rotation: new key issued, old one valid for `graceHours` (default 24, 0 = immediate revoke).
- LIVE keys need merchant status ACTIVE (EXTERNAL DEPENDENCY: onboarding/KYC, then ops activation).
- Webhook signing secret: AES-256-GCM encrypted at rest (`WEBHOOK_SECRET_KEY`, base64 32 bytes). URLs: https only, SSRF guard.
- Internal endpoints (gateway only): `POST /internal/v1/api-keys/verify`, `GET /internal/v1/users/{id}/merchant`.
Env: POSTGRES_USER, POSTGRES_PASSWORD, INTERNAL_SERVICE_TOKEN, API_KEY_HASH_PEPPER, WEBHOOK_SECRET_KEY.
