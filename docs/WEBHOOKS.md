# Webhooks / provider callbacks
Endpoint: `POST /api/v1/webhooks/{provider}` (public through the gateway; authenticity = provider signature).
PayU: set surl/furl to `<PUBLIC_BASE_URL>/api/v1/webhooks/payu` (done automatically by the orchestrator for browser returns). Optionally also configure the same URL as PayU's server webhook in the PayU dashboard (recommended for customers who close the browser before returning).
Order of work: verify hash -> fingerprint -> persist -> de-duplicate -> orchestrator verify (provider `verify_payment`, amount check) -> state machine. See services/webhook-service/README.md for failure handling (REJECTED / RETRYING / DEAD / UNMATCHED).
Never trusted: unsigned callbacks, browser-supplied status, amounts in the callback (amount is re-checked against our payment through the provider status API).
