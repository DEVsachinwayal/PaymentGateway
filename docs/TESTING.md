# TESTING

- Unit/integration tests per module: `mvn -pl services/<svc> -am clean test` (Testcontainers Postgres/Kafka; set `TESTCONTAINERS_RYUK_DISABLED=true` on this machine). Use `clean`: stale classes once hid a new test; if the IDE recompiles into target/ and a test fails with "Unresolved compilation problems", just rerun with `clean`.
- Stop the dev stack before building (`bash scripts/dev-stop.sh`): running services lock their jars on Windows.
- Surefire runs `*Test` classes only. External PayU tests are opt-in (`PayULiveSandboxTest`); unit tests use a fake PayU that checks real hashes (D23).
- Event pipeline smoke test against the running stack: `bash scripts/smoke-events.sh` (simulated provider success, dev only).
- Counts (2026-10-09): common 14, gateway 15, auth 8, merchant 8, order 7, payment 17, provider 24 (+1 opt-in), orchestrator 15, webhook 9, refund 14, ledger 13, settlement 10, notification 12.
- Still to do: JMeter load tests (M14), contract tests per provider, CI pipeline (M15).
