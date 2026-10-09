# CONFIGURATION

Values are read from environment variables (names in `.env.example`, real values in the git-ignored `.env` locally or the secret store in production). Defaults in each service's `application.yml` are for local development only. Secrets have **no defaults**: a service refuses to start without them.

## Secrets (never commit, never log)
| Variable | Used by | Meaning |
|---|---|---|
| POSTGRES_USER / POSTGRES_PASSWORD | all DB services | database login |
| REDIS_PASSWORD | gateway | rate-limit store |
| INTERNAL_SERVICE_TOKEN | every service + gateway | shared token proving a call came through the gateway/another service (>= 16 chars) |
| JWT_PRIVATE_KEY | auth-service | RS256 signing key (base64 PKCS#8, RSA >= 2048) |
| JWT_PUBLIC_KEY | gateway | verification key(s), comma separated while rotating |
| JWT_SECRET | auth + gateway | HS256 dev fallback (>= 32 bytes); ignored by the gateway when JWT_PUBLIC_KEY is set |
| API_KEY_HASH_PEPPER | merchant | HMAC key for API-key hashes |
| WEBHOOK_SECRET_KEY | merchant | AES-256 key (base64) protecting webhook signing secrets |
| CHECKOUT_TOKEN_SECRET | orchestrator | signs hosted-checkout links |
| PAYU_MERCHANT_KEY / PAYU_MERCHANT_SALT | provider-integration | PayU credentials |

## Wiring and behaviour
| Variable | Default | Meaning |
|---|---|---|
| `<SERVICE>_DB_URL` (AUTH, MERCHANT, ORDER, PAYMENT, PROVIDER, ORCHESTRATOR, WEBHOOK, REFUND, LEDGER, SETTLEMENT, NOTIFICATION) | `jdbc:postgresql://localhost:5433/apg_<x>` | JDBC URL |
| `<SERVICE>_SERVICE_URI` | `http://localhost:<port>` | where services find each other |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 | Kafka |
| APG_EVENTS_ENABLED | false (dev-start sets true) | publish outbox events / consume topics |
| REDIS_HOST / REDIS_PORT | localhost / 6379 | |
| PUBLIC_BASE_URL (orchestrator) | http://localhost:8080 | public address used in provider return URLs |
| CORS_ALLOWED_ORIGINS (gateway) | http://localhost:5173 | dashboard origin(s) |
| GATEWAY_MANAGEMENT_PORT | 8180 | gateway health/metrics (internal) |
| PAYU_BASE_URL | https://test.payu.in | sandbox; production URL only after live onboarding |
| LEDGER_FEE_BPS / LEDGER_FEE_FIXED_MINOR | 0 / 0 | placeholder pricing |
| APG_TRACING_ENDPOINT | unset (export off) | OTLP/HTTP traces URL |
| SPRING_PROFILES_ACTIVE | unset | `prod` = structured JSON logs |
| APG_JAVA_OPTS (dev-start) | -Xms64m -Xmx384m ... | JVM options for local JVMs |

## Development-only switches (must be OFF in production)
`NOTIFICATION_ALLOW_INSECURE_URLS`, `MERCHANT_ALLOW_INSECURE_WEBHOOK_URLS` (allow http/localhost webhook targets), `ORCHESTRATOR_VERIFICATION_ENABLED=false` and large `GATEWAY_RATE_LIMIT_RPS/BURST` (load tests only; `scripts/perf-start.sh`).

## Profiles
local = default; tests = their own properties inside each test class; production = `SPRING_PROFILES_ACTIVE=prod` plus the secrets above from the secret store.
