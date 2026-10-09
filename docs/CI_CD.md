# CI / CD (spec 24)

Status: workflows are written and their YAML is validated locally (parsed; compose files checked with `docker compose config`). They have **never run on GitHub** (this repository has no remote yet), so expect to fix small things on the first run. Action versions are pinned by tag; pin by commit SHA for production hardening.

## Pipelines
| Workflow | Trigger | Jobs |
|---|---|---|
| `.github/workflows/ci.yml` | push to `main`, every pull request | **backend** `mvn verify` (all unit/integration tests with Testcontainers: Postgres, Kafka, Redis) - **frontend** type-check + build + `npm audit` - **secrets-scan** gitleaks over full history - **dependency-scan** OWASP dependency-check (fails on CVSS >= 7; needs the optional `NVD_API_KEY` secret for reliable NVD downloads) - **images** build each of the 12 service images (matrix) and scan them with Trivy (HIGH/CRITICAL with fix available fails) - **compose-config** validates the compose overlays |
| `.github/workflows/release.yml` | tag `vX.Y.Z` | push 12 service images + dashboard to OCIR - **deploy** (GitHub environment `production` with required reviewers = manual approval) runs `scripts/deploy.sh <tag>` on the VM over SSH; failed health checks roll back automatically |
| `.github/dependabot.yml` | weekly | Maven, npm, GitHub Actions, Docker base image updates as PRs |

## Required repository configuration
- Variables: `OCIR_REGISTRY`, `OCIR_NAMESPACE`. Secrets: `OCIR_USERNAME`, `OCIR_TOKEN`, `DEPLOY_SSH_KEY`, `DEPLOY_HOST`, `DEPLOY_USER`, optional `NVD_API_KEY`.
- Environment `production`: required reviewers, restrict to tags `v*`. Branch protection on `main`: require CI, no force push.
- CI deliberately has **no application secrets**: tests use fakes (fake PayU, fake peers). The live PayU sandbox test stays opt-in and local.

## Local equivalents
`mvn -B verify` (stop the dev stack first on Windows: running services lock their jars), `cd frontend && npm run build`, `docker build --build-arg SERVICE=<svc> .`, `docker compose -f docker-compose.yml -f docker-compose.apps.yml config -q`.

## Known gaps
- No SBOM/signing of images; no staging environment/promotion; no database migration dry-run job against a production-sized copy; JMeter is not part of CI (run it on demand, docs/PERFORMANCE.md).
- Test time: the full suite takes ~10 minutes on a developer laptop; split by module (`-pl`) if CI time matters.
