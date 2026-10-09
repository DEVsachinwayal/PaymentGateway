# ORACLE CLOUD (OCI) - target environment (spec 23)

Status: **design + templates, not applied**. Nothing here has been run against a real tenancy (no OCI account was available); every step below needs your account, region and decisions. Treat the commands as templates to review.

## Target (pilot) architecture
```
Internet --TLS--> OCI Load Balancer (public subnet, certificate, WAF optional)
                       |  HTTP 8000 (health: /healthz)
                       v
        Compute VM (private subnet): docker compose = dashboard(nginx) + api-gateway + 11 services
                       |
        +--------------+---------------+-------------------+
        v              v               v                   v
   OCI Database    OCI Cache       OCI Streaming       OCI Vault (secrets)
   with PostgreSQL (Redis)         (Kafka API)         OCIR (images)
```
Single VM is a pilot shape; scale-out = more VMs behind the load balancer (services are stateless; scheduled workers use DB leases / unique keys so several instances do not double-process) or OKE (Kubernetes) later.

## What you must provide (external dependencies)
- OCI tenancy, compartment, region, budget/quota; an IAM user or instance principal for CI with push rights to OCIR (auth token).
- Domain name + TLS certificate (OCI Certificates or imported). Public hostname -> `APG_PUBLIC_URL` (used for PayU surl/furl and CORS).
- Decisions: managed PostgreSQL vs container, Redis, Kafka (OCI Streaming vs self-hosted Kafka); backup/retention policy; log retention.
- PayU **live** account, KYC, and the legal/licence items in docs/TODO.md. Until then keep `PAYU_BASE_URL=https://test.payu.in` (sandbox) - a pilot on OCI with sandbox money is a good first target.

## Steps (template)
1. **Network**: VCN with a public subnet (load balancer) and a private subnet (VM, database, cache). Security lists / NSGs: LB 443 from anywhere; VM 8000 only from the LB; database 5432, Redis 6379, Streaming 9092 only from the VM's NSG; SSH only via bastion / OCI Bastion service.
2. **Registry**: create OCIR repositories `apg/<service>` (12) and `apg/dashboard`; create an auth token; set the GitHub variables/secrets listed in `.github/workflows/release.yml`.
3. **Secrets**: create one Vault secret per name in `.env.example`. On the VM an instance principal fetches them into `/opt/apg/.env` (mode 600, owner = deploy user) at deploy time, e.g. `oci secrets secret-bundle get --secret-id <ocid> --query 'data."secret-bundle-content".content' --raw-output | base64 -d`. Generate keys with `scripts/gen-jwt-keys.sh` on a trusted machine, never on CI. Rotate `JWT_PRIVATE_KEY` as described in that script.
4. **Data services**: create the PostgreSQL DB system and the 11 databases (`infrastructure/postgres/init/01-create-databases.sh` has the list); create migration and runtime roles; set `*_DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` accordingly. Redis: set `REDIS_HOST/PORT/PASSWORD`. Kafka/Streaming: set `KAFKA_BOOTSTRAP_SERVERS`; OCI Streaming needs SASL_SSL (`spring.kafka.properties.*` / env `SPRING_KAFKA_PROPERTIES_...`) - **not yet wired or tested**, and topics/DLTs must be created with the partition counts in docs/EVENTS.md.
5. **VM**: Oracle Linux/Ubuntu, Docker + compose plugin, a `deploy` user, `/opt/apg` = a checkout of this repository at the release tag (only compose files and scripts are used) + `.env`. First deploy: `./scripts/deploy.sh vX.Y.Z`.
6. **Load balancer**: backend set = VM:8000, health check `GET /healthz` (expects 200 `ok`), TLS listener on 443, HTTP 80 redirect, idle timeout >= 60 s, forward `X-Forwarded-For/Proto` (the dashboard proxy passes them on).
7. **Observability**: ship container stdout (JSON) with the OCI Logging agent; run Prometheus/Grafana from the `observability` compose profile on the VM (or OCI Monitoring) scraping the private actuator ports; add Alertmanager routing (docs/OBSERVABILITY.md).
8. **PayU**: set the sandbox/live webhook URL in the PayU dashboard to `https://<host>/api/v1/webhooks/payu`; surl/furl are generated from `APG_PUBLIC_URL`.
9. **Go-live checklist**: docs/RELEASE_READINESS.md.

## Cost/ops notes
Pilot footprint ~ 1 VM (4 OCPU / 16 GB) + small managed Postgres + load balancer (flexible shape, 10 Mbps) + a few GB of logs; confirm against your OCI pricing and free-tier eligibility.
