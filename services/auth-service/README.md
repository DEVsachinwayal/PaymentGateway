# auth-service (port 8081, DB `apg_auth`)
Registration, login, JWT access tokens (15 min), rotating opaque refresh tokens (7 d, SHA-256 hashed at rest, reuse => whole family revoked), lockout (5 failures => 15 min), login audit.
Run: set `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET` (>=32 bytes) then `java -jar target/auth-service-*.jar`. Tests: `mvn test -pl services/auth-service -am` (needs Docker; set `TESTCONTAINERS_RYUK_DISABLED=true` on this machine).
Self-registration only creates `MERCHANT_ADMIN`; privileged roles are granted by SYSTEM_ADMIN (admin API: TODO).
