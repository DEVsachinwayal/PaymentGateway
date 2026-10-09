# RELEASE READINESS (spec 26 gates, M16)

Honest status per gate. "Evidence" says where to look or how to reproduce. Anything marked **OPEN** blocks a production launch; most OPEN items are external (provider, cloud, legal) rather than code.

| Gate | Status | Evidence / what is missing |
|---|---|---|
| Build | PASS | `mvn clean package` builds libs + 12 services; dashboard builds (`npm run build`); Dockerfiles exist and the dashboard image was built and smoke-tested; Java service images build in CI (first CI run still to be observed) |
| Tests | PASS (local) | 190+ automated tests across modules (docs/TESTING.md); full run green on 2026-10-09. CI has never run on GitHub (no remote yet) |
| Security | PARTIAL | RS256, masked logs, audit trail, header/SSRF/idempotency/ledger-integrity controls are tested (docs/SECURITY.md). **OPEN:** dependency + secret scan results (CI jobs written, never executed), pen test, TLS, DB least-privilege roles, operator RBAC/MFA |
| Payments | PASS in sandbox | one real PayU sandbox payment completed end to end: CREATED -> PENDING -> PROCESSING -> SUCCESS (verification worker confirmed capture with PayU, authenticated callback arrived later and was a no-op), ledger CAPTURE posted. Failure, timeout/unknown, duplicate and amount-mismatch scenarios are covered by tests with a hash-checking fake PayU. **OPEN:** real-sandbox failure and abandoned-checkout runs |
| Webhooks | PASS | provider callbacks: forged/duplicate/replay/unmatched/dead handling tested; real PayU callback accepted. Merchant webhooks: signature, retry, DEAD, resend, SSRF tests |
| Money | PASS in sandbox | ledger balance enforced by the database and tested; refund over-limit, duplicate, partial, unknown-outcome tests; a real PayU sandbox refund was accepted ("Refund Request Queued"). **OPEN:** confirmation of PayU's `check_action_status` response shape (the first real status poll failed on a network error; see WORK.md) |
| Reconciliation | PASS (tested), PARTIAL (real) | all mismatch types create visible exceptions (tests). **OPEN:** PayU's real settlement report format and automatic fetch (needs PayU settlement API access); "missing in settlement" detection |
| Observability | PASS (local) | JSON logs, metrics, traces (export off by default), 16 alert rules checked with promtool, dashboard; Prometheus scraped all 12 targets live. **OPEN:** Alertmanager receivers, trace backend, running on OCI |
| Deployment | OPEN | compose overlays, scripts, workflows written and validated offline; **never run on OCI or GitHub** (no accounts) |
| Recovery | OPEN | backup script exists; **restore has not been tested**; no DR plan |
| Operations | PARTIAL | runbooks (docs/TROUBLESHOOTING.md), architecture, API, events docs complete; operator UI/RBAC missing (internal APIs + SQL only) |
| External readiness | OPEN | PayU live onboarding + KYC, legal entity and RBI payment-aggregator position, PCI scoping, domain/TLS, OCI tenancy, email/SMS provider (docs/TODO.md) |

## Before the first real merchant (checklist)
1. External: legal/licence decision, PayU live account (KYC), contracts, domain + TLS, OCI tenancy.
2. Run CI on GitHub once; fix findings from OWASP/Trivy/gitleaks.
3. Deploy to OCI with sandbox PayU; run the JMeter plan there (docs/PERFORMANCE.md); soak test >= 30 min.
4. Test backup + restore; write the DR procedure; set up Alertmanager routing and on-call.
5. Create least-privilege DB roles; move secrets into OCI Vault; rotate every dev secret (the local `.env` values must never be reused).
6. Operator tooling: activate merchants after KYC (SANDBOX -> ACTIVE), grant APG_OPERATIONS/APG_FINANCE roles, an authenticated operator UI for exceptions/payouts.
7. Run the real-sandbox matrix: success, failure, user-cancel, abandoned checkout, timeout, duplicate callback, partial/full refund, settlement import with a real report.
8. Pay-in/pay-out money rules agreed with finance: fees, GST, refund fee policy, settlement schedule, chargebacks (ADJUSTMENT items today need a manual decision).
9. Privacy/retention: what customer data is kept, for how long, and deletion procedure.
10. Sign-off against the spec section 34 acceptance statement.
