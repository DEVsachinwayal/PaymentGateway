# TODO
## Pending milestones
M2 Gateway+Auth, M3 Merchant, M4 Order, M5 Payment, M6 Orchestration, M7 Provider+PayU, M8 Webhook, M9 Refund, M10 Ledger, M11 Settlement/Recon, M12 Kafka/Redis/Notifications, M13 Security/Observability, M14 Performance, M15 OCI/CI-CD, M16 Release readiness.
## EXTERNAL DEPENDENCIES (not done, user/provider must supply)
- PayU sandbox key/salt (needed at M7)
- PayU production onboarding, KYC, production URLs
- OCI account/billing, domain, TLS certificate
- Email/SMS provider (if used), PCI/compliance assessment, banking/acquirer agreements
## Open items
- [ ] Structured JSON logging config (add with first service, logback + MDC correlationId)
- [ ] Admin API to grant APG_OPERATIONS/APG_FINANCE/SYSTEM_ADMIN roles (auth-service)
- [ ] Rate limiter key from X-Forwarded-For behind OCI LB (M15)
- [ ] Move JWT to asymmetric keys / JWKS (M13)
- [ ] Gateway: structured JSON logs (M13)
- [ ] Frontend (React dashboard): login, API keys, webhook endpoints, orders — not started
- [ ] Team management: add MERCHANT_DEVELOPER users to a merchant (merchant_users supports it, no API yet)
- [ ] Ops API to activate merchants (SANDBOX -> ACTIVE) after KYC (EXTERNAL DEPENDENCY)
- [ ] Re-validate webhook URL resolved IP at delivery time (M12)
- [ ] Short Redis cache for API key verification (M12/M14)
- [ ] BLOCKER (user): valid PayU TEST key + salt (current pair gets "Invalid Hash."). Needed for M7 sandbox verification.
