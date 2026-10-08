# TODO
## Pending milestones
M2 Gateway+Auth, M3 Merchant, M4 Order, M5 Payment, M6 Orchestration, M7 Provider+PayU, M8 Webhook, M9 Refund, M10 Ledger, M11 Settlement/Recon, M12 Kafka/Redis/Notifications, M13 Security/Observability, M14 Performance, M15 OCI/CI-CD, M16 Release readiness.
## EXTERNAL DEPENDENCIES (not done, user/provider must supply)
- PayU sandbox key/salt (needed at M7)
- PayU production onboarding, KYC, production URLs
- OCI account/billing, domain, TLS certificate
- Email/SMS provider (if used), PCI/compliance assessment, banking/acquirer agreements
## Open items
- [ ] Start Docker Desktop and verify `docker compose up -d` healthy (M1 runtime verification pending)
- [ ] Structured JSON logging config (add with first service, logback + MDC correlationId)
