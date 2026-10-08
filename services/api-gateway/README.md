# api-gateway (port 8080, Spring Cloud Gateway / WebFlux)
Single public entry. Adds/propagates `X-Correlation-Id`, validates end-user JWT (HS secret `JWT_SECRET`, shared with auth-service) on all non-public routes, strips client-supplied `X-User-*` and forwards trusted `X-User-Id/Email/Roles`, Redis rate limiting (per client IP), 256 KB request-size limit, CORS allow-list (`CORS_ALLOWED_ORIGINS`), secure headers. No business logic, no DB.
Env: `JWT_SECRET`, `REDIS_HOST/PORT/PASSWORD`, `AUTH_SERVICE_URI`, `CORS_ALLOWED_ORIGINS`.
Add a route per new service in `GatewayConfig.apgRoutes`. Merchant API-key auth is added in M3.
Tests: `mvn test -pl services/api-gateway` (Docker needed for Redis container; set `TESTCONTAINERS_RYUK_DISABLED=true`).
