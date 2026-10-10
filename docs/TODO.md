# TODO

## EXTERNAL DEPENDENCIES (not code: you / providers / legal must supply)
- PayU sandbox key/salt: DONE (in local .env). PayU **live** onboarding, KYC, production URLs and the settlement API/report access.
- Legal/regulatory position for holding other merchants' funds (RBI payment aggregator rules) or a licensed partner agreement; PCI-DSS scoping; banking/acquirer agreements.
- OCI tenancy + billing, domain, TLS certificate, OCIR + Vault + managed Postgres/Redis/Streaming decisions (docs/ORACLE_CLOUD.md).
- GitHub repository + secrets for CI/CD (docs/CI_CD.md); optional NVD API key.
- Email/SMS provider if email notifications are wanted (only the webhook channel exists).
- Business decisions: fee schedule (placeholder 0), GST on fees, refund fee policy, settlement schedule, chargeback handling, data retention.

## Engineering backlog (ordered roughly by value)
- [x] Dashboard screens: refunds, settlements, ledger statement, webhook delivery history + resend, activity log (done)
- [ ] Dashboard: test-mode toggle, team management, server-side stats endpoint for the overview
- [ ] Execute the JMeter plan (needs JMeter installed); soak test; repeat on OCI-sized hardware
- [ ] Confirm PayU `check_action_status` success mapping when the sandbox refund flips from pending (first poll returned "pending" = PROCESSING)
- [ ] Operator access: authenticated ops API/UI, grant APG_OPERATIONS/APG_FINANCE roles, activate merchants (SANDBOX -> ACTIVE) after KYC, 2-person approval for ledger reversals
- [ ] Least-privilege DB roles (migration role vs runtime role); mTLS or mesh between services
- [ ] Team management (add MERCHANT_DEVELOPER users to a merchant: `merchant_users` supports it, no API)
- [ ] Settlement: automatic PayU fetch adapter, "missing in settlement" detection, payout posting in the ledger when a merchant payout is marked PAID
- [ ] Merchant `allowedReturnUrls` enforcement on payment `returnUrl`; require customer email in production
- [ ] Provider failover when a second provider exists (D24); provider health-based routing
- [ ] Refactor duplicated internal HTTP client plumbing (RestClient setup repeated in 7 services) into apg-common
- [ ] Email channel + notification templates (needs a provider)
- [ ] Rate limiting per API key/merchant (today per client address); Redis cache for provider health
- [ ] Image signing/SBOM; staging environment; migration dry-run job
- [x] Payment links (done: one-time/reusable, expiry, cancel, public page, dashboard)
- [ ] Payment links follow-ups: QR code, send by SMS/email, partial payments, link-level analytics
- [ ] Phase 2 products (outside the spec): invoices, subscriptions
