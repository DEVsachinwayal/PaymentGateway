# API Reference
Envelope: `{success,data,error,requestId,timestamp}`; error = `{code,message,details[]}`. Header `X-Correlation-Id` is echoed/generated.

## Auth (auth-service, via gateway `/api/v1/auth/**`)
| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | /api/v1/auth/register | email, password(10-72), fullName | 201 {userId,email} | 400 VALIDATION_FAILED, 409 EMAIL_ALREADY_REGISTERED |
| POST | /api/v1/auth/login | email, password | 200 {accessToken,refreshToken,tokenType,expiresInSeconds} | 401 INVALID_CREDENTIALS, 423 ACCOUNT_LOCKED |
| POST | /api/v1/auth/refresh | refreshToken | 200 new token pair (old one revoked) | 401 INVALID_REFRESH_TOKEN / REFRESH_TOKEN_EXPIRED / REFRESH_TOKEN_REUSED |
| POST | /api/v1/auth/logout | refreshToken | 204 (revokes session family) | |
Access JWT: HS512/HS256 shared secret, claims iss=apg-auth, sub=userId, email, roles[], jti, exp.
