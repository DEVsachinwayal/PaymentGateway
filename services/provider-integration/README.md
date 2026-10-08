# provider-integration (port 8085, DB `apg_provider`)
Provider adapters behind the `PaymentProvider` port (`createPayment`, `getPaymentStatus`, `refund`, `verifyWebhook`, `health`). PayU is the first adapter (`com.apg.provider.payu`); PayU DTOs/vocabulary never leave that package.
- Internal API (token protected, not routed by gateway): `GET /internal/v1/providers`, `POST /{provider}/checkout`, `GET /{provider}/payments/{ref}/status`, `POST /{provider}/callbacks/verify`, `POST /{provider}/refunds`, `GET /{provider}/health`.
- Provider trouble => HTTP 502 + code PROVIDER_UNAVAILABLE / PROVIDER_RATE_LIMITED / PROVIDER_BAD_RESPONSE: the caller must treat the money outcome as UNKNOWN (verify later), never as failed.
- Status mapping: SUCCESS only for PayU `success`+`captured`; FAILED only for `failure`/`failed`/`usercancelled`; everything else PENDING; unknown txn NOT_FOUND.
- A verified callback hash proves authenticity only; callers still confirm via `status` before changing state.
- Circuit breaker: 5 consecutive failures => 30 s open (PayU rate-limits clients that send bad requests).
- Tables: provider_configs (non-secret), provider_attempts (sanitized call log), provider_audit. Secrets: env only (PAYU_MERCHANT_KEY, PAYU_MERCHANT_SALT, PAYU_BASE_URL).
- Tests: hash vectors computed independently with openssl; fake PayU server that validates request hashes; opt-in live sandbox test: `PAYU_LIVE_TEST=true` with the PAYU_* env set (one harmless call).
