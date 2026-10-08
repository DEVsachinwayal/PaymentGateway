# APG – WORK.md: instructions for any AI session (read first; renamed from CLAUDE.md)

Project: Autonomous Payment Gateway. Spec = `docs/MASTER_SPEC_EXTRACT.txt` (extracted from the .docx; authoritative).
User communicates in Marathi/English. User asked: build the whole project per spec, keep notes so a new chat can continue, commit after each task.

## Resume protocol (new chat)
1. Read `docs/WORK_LOG.md` (last entries), `docs/TODO.md`, `docs/DECISIONS.md`.
2. Run `git log --oneline | head` and `git status`.
3. Continue from the "Next step" in the last WORK_LOG entry. One milestone (M0..M16) at a time.
4. After each task: update WORK_LOG, CHANGELOG, TODO (+DECISIONS), then `git commit`.

## Environment facts
- Windows 11. Real JDK: `C:\Program Files\Java\jdk-25.0.4.1` (system JAVA_HOME is wrong -> set `JAVA_HOME` per shell before mvn).
- Maven: `D:\Tools\apache-maven-3.9.16`. Node 22. Docker CLI present; daemon may need to be started (Docker Desktop).
- Stack decision: Spring Boot 3.5.16, Java release 21 (runs on 25), multi-module Maven. See DECISIONS.md.
- Never commit secrets; `.env` is git-ignored.

## Milestones (17 total: M0..M16) – update status after each commit
| # | Milestone | Status |
|---|---|---|
| M0 | Repo + docs skeleton | DONE |
| M1 | Foundation (parent pom, apg-common, docker infra) | DONE |
| M2 | API Gateway + Auth (JWT, refresh, roles) | DONE |
| M3 | Merchant (API keys, webhook config) - backend done, dashboard UI pending | DONE (backend) |
| M4 | Order | NEXT |
| M5 | Payment (state machine, idempotency) | todo |
| M6 | Orchestrator | todo |
| M7 | Provider adapter + PayU sandbox (needs user's PayU key/salt) | todo |
| M8 | Webhook | todo |
| M9 | Refund | todo |
| M10 | Ledger | todo |
| M11 | Settlement + Reconciliation | todo |
| M12 | Kafka/Redis/Notifications/DLQ | todo |
| M13 | Security + Observability | todo |
| M14 | Performance (JMeter) | todo |
| M15 | OCI + CI/CD (needs user's OCI account) | todo |
| M16 | Release readiness | todo |
Frontend (React merchant dashboard) is built incrementally alongside M3..M11.
