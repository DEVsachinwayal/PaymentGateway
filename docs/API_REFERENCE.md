# API Reference
Envelope: `{success,data,error,requestId,timestamp}`; error = `{code,message,details[]}`. Header `X-Correlation-Id` is echoed/generated.

## Auth (auth-service, via gateway `/api/v1/auth/**`)
| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | /api/v1/auth/register | email, password(10-72), fullName | 201 {userId,email} | 400 VALIDATION_FAILED, 409 EMAIL_ALREADY_REGISTERED |
| POST | /api/v1/auth/login | email, password | 200 {accessToken,refreshToken,tokenType,expiresInSeconds} | 401 INVALID_CREDENTIALS, 423 ACCOUNT_LOCKED |
| POST | /api/v1/auth/refresh | refreshToken | 200 new token pair (old one revoked) | 401 INVALID_REFRESH_TOKEN / REFRESH_TOKEN_EXPIRED / REFRESH_TOKEN_REUSED |
| POST | /api/v1/auth/logout | refreshToken | 204 (revokes session family) | |
Access JWT: HS512/HS256 shared secret, claims iss=apg-auth, sub=userId, email, roles[], jti, exp.

## Merchant (merchant-service, via gateway; JWT dashboard users)
Roles: write ops = MERCHANT_ADMIN; read ops = MERCHANT_ADMIN or MERCHANT_DEVELOPER. API-key callers cannot use these endpoints.
| Method | Path | Notes |
|---|---|---|
| POST | /api/v1/merchants | body name, supportEmail, [legalName, defaultCurrency]; 201; 409 MERCHANT_ALREADY_EXISTS; new merchants are SANDBOX |
| GET | /api/v1/merchants/me | |
| GET/PUT | /api/v1/merchants/me/settings | defaultCurrency, allowedReturnUrls[] (https) |
| POST | /api/v1/api-keys | {label, mode TEST/LIVE}; 201 returns `secret` ONCE (`sk_test_<id>.<secret>`); LIVE => 403 LIVE_MODE_NOT_ENABLED until merchant ACTIVE |
| GET | /api/v1/api-keys | never returns secrets |
| POST | /api/v1/api-keys/{id}/rotate | {graceHours 0-72, default 24}; returns new key once |
| DELETE | /api/v1/api-keys/{id} | revoke; 204 |
| POST/GET/DELETE | /api/v1/webhook-endpoints[/{id}] | {url (https, SSRF-guarded), events[]}; signingSecret returned once |
Merchant API authentication: `Authorization: Bearer sk_test_...` or `X-Api-Key`; allowed paths: /api/v1/orders, /payments, /refunds.
Allowed webhook events: payment.success, payment.failed, payment.unknown, refund.success, refund.failed, settlement.created.

## Orders (order-service; API key or dashboard user)
| Method | Path | Notes |
|---|---|---|
| POST | /api/v1/orders | Header `Idempotency-Key` required. Body: amount (long, minor units, >0), currency (INR), [merchantOrderRef, description, customer{name,email,phone}, items[{name,quantity,unitAmount}] (sum must equal amount), metadata{<=20 string pairs}]. 201 order. Errors: 400 VALIDATION_FAILED/CURRENCY_NOT_SUPPORTED/AMOUNT_MISMATCH/AMOUNT_TOO_LARGE/INVALID_METADATA/IDEMPOTENCY_KEY_REQUIRED, 409 DUPLICATE_ORDER_REF/IDEMPOTENCY_IN_PROGRESS, 422 IDEMPOTENCY_KEY_REUSED |
| GET | /api/v1/orders/{id} | 404 ORDER_NOT_FOUND for other merchants' orders |
| GET | /api/v1/orders?page=0&size=20 | newest first, size max 100 |
Order status: CREATED -> PAID / CANCELLED / EXPIRED (updated by payment flow later).
