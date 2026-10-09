# INTEGRATION GUIDE (for merchants)

Audience: a developer who wants to accept payments on their own website with APG. A working example is in `examples/sample-shop` (about 120 lines of Node, no dependencies).

## The idea in one picture
```
Your server --(1) create order + payment + checkout (secret API key)--> APG
Customer    <--(2) redirect to the checkout link--                        Your server
Customer    --(3) pays on the provider's hosted page (card/UPI/netbanking)--> PayU (APG never sees card data)
APG         --(4) verifies the result with the provider--> payment becomes SUCCESS or FAILED
APG         --(5) signed webhook "payment.success"--> Your server  => THIS marks your order paid
Customer    --(6) lands on your return page (informational only)
```

## 1. One-time setup
1. Register in the dashboard, create your business (starts in **test mode**).
2. **API keys** -> create a TEST key (`sk_test_...`). The secret is shown once: store it as a server-side secret. Never ship it to a browser, mobile app or repository.
3. **Webhooks** -> add your endpoint URL (https; `http://localhost` only in a local dev stack) and choose the events: `payment.success`, `payment.failed`, `refund.success`, `refund.failed`. Store the **signing secret** (shown once).
4. Optional: **Settings** -> allowed return URLs.

## 2. Start a payment (your server)
All calls: `Authorization: Bearer sk_test_...`, `Content-Type: application/json`. Money is **integer paise** (INR 499.00 = `49900`). Every POST needs a fresh `Idempotency-Key` (a UUID or a stable key such as `order-<your id>`): repeating the same key returns the first result instead of creating a second order or payment.
```
POST /api/v1/orders       {"amount":49900,"currency":"INR","merchantOrderRef":"ORDER-1042","description":"Book","customer":{"name":"Asha","email":"asha@example.com","phone":"9876543210"}}
  -> 201 {"data":{"id":"<orderId>", ...}}
POST /api/v1/payments     {"orderId":"<orderId>","returnUrl":"https://shop.example/thanks"}     (returnUrl optional, https only)
  -> 201 {"data":{"id":"<paymentId>","status":"CREATED", ...}}
POST /api/v1/payments/<paymentId>/checkout     {}
  -> 201 {"data":{"checkoutUrl":"https://.../checkout/<token>", "status":"PENDING"}}
```
Redirect the customer's browser to `checkoutUrl` (HTTP 303). The link works for 30 minutes and for one payment. Do not store or log it longer than needed.

## 3. After the customer pays
- **Return page** (`returnUrl`, or APG's neutral result page when you gave none): shows "we are confirming your payment". The query string (`status`, `paymentId`) is a hint for the UI only. **Never mark an order paid from the return page.**
- **Webhook** (the source of truth): APG calls your endpoint with a signed JSON body:
```
POST https://shop.example/apg-webhook
X-APG-Signature: t=1760000000,v1=<hex>        X-APG-Event-Id: <uuid>        X-APG-Event-Type: payment.success
{"id":"<eventId>","type":"payment.success","createdAt":"...","data":{"paymentId":"...","orderId":"...","amount":49900,"currency":"INR","status":"SUCCESS","provider":"payu"}}
```
Verify before trusting (sample code below): recompute `HMAC-SHA256(signingSecret, t + "." + rawBody)` over the **raw bytes** you received, compare in constant time, reject timestamps older than 5 minutes, ignore an event id you already handled (events are delivered **at least once**), check that `amount` and `orderId` match your own order, then answer **2xx quickly** (do heavy work afterwards). Any other answer (or no answer within 10 s) makes APG retry: 10 s, 20 s, 40 s ... up to 1 h, 8 attempts, then the delivery shows as DEAD in the dashboard where you can **Resend** it.
- **Status check** if you want to be sure at any time: `GET /api/v1/payments/<paymentId>/status` returns the verified status and full history. A pending payment is normal for a few minutes.

### Signature verification
Node.js
```js
const [t, v1] = req.headers['x-apg-signature'].split(',').map((p) => p.split('=')[1])
const expected = crypto.createHmac('sha256', SIGNING_SECRET).update(t + '.' + rawBody).digest('hex')
const ok = expected.length === v1.length && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(v1)) && Math.abs(Date.now() / 1000 - t) < 300
```
Python
```python
t, v1 = [p.split("=")[1] for p in request.headers["X-APG-Signature"].split(",")]
expected = hmac.new(SECRET.encode(), (t + "." + raw_body.decode()).encode(), hashlib.sha256).hexdigest()
ok = hmac.compare_digest(expected, v1) and abs(time.time() - int(t)) < 300
```
Java
```java
Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String expected = HexFormat.of().formatHex(mac.doFinal((t + "." + rawBody).getBytes(UTF_8)));
boolean ok = MessageDigest.isEqual(expected.getBytes(UTF_8), v1.getBytes(UTF_8)) && Math.abs(Instant.now().getEpochSecond() - Long.parseLong(t)) < 300;
```
PHP
```php
[$t, $v1] = array_map(fn($p) => explode('=', $p)[1], explode(',', $_SERVER['HTTP_X_APG_SIGNATURE']));
$expected = hash_hmac('sha256', $t . '.' . $rawBody, $secret);
$ok = hash_equals($expected, $v1) && abs(time() - (int)$t) < 300;
```
Use the raw body exactly as received (not re-serialised JSON), or the signature will not match.

## 4. Refunds
```
POST /api/v1/refunds {"paymentId":"<paymentId>","amount":10000,"reason":"customer request"}     (Idempotency-Key required; amount optional = everything left)
  -> 202 {"data":{"id":"<refundId>","status":"CREATED"}}
GET  /api/v1/refunds/<refundId>
```
Statuses: CREATED -> PROCESSING (accepted by the provider) -> SUCCESS or FAILED. Total refunds can never exceed the payment (422 `REFUND_EXCEEDS_PAYMENT`). You receive `refund.success` / `refund.failed` webhooks. A refund in `VERIFY_REQUIRED` is being checked by APG operations; the amount stays reserved until then.

## 5. Errors
Every response is `{"success":bool,"data":...,"error":{"code","message","details"},"requestId"}`. Quote `requestId` when you contact support.
| Status / code | Meaning | What to do |
|---|---|---|
| 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED` | bad input (floats, unknown fields, missing key) | fix the request |
| 401 `INVALID_API_KEY` / 403 `FORBIDDEN_FOR_API_KEY` | wrong key / key used on a dashboard endpoint | check the key and the path |
| 404 | not found or belongs to another merchant | - |
| 409 `PAYMENT_ALREADY_EXISTS`, `ORDER_NOT_PAYABLE`, `IDEMPOTENCY_IN_PROGRESS` | state conflict / same key still running | do not retry blindly; read the payment status |
| 422 `IDEMPOTENCY_KEY_REUSED`, `REFUND_EXCEEDS_PAYMENT` | same key with a different body / over-refund | use a new key / smaller amount |
| 429 | rate limit | back off |
| 502/503 | dependency trouble | retry the SAME request with the SAME Idempotency-Key |

## 6. Rules that keep you safe
1. The secret key lives on your server only. Rotate it from the dashboard (old key keeps working for the grace period you choose).
2. Order becomes "paid" only from a verified webhook (or the status API), and only if amount and order match.
3. Handle each event id once; answer 2xx for repeats.
4. Never put the checkout link or API key in logs or analytics.
5. If your server was down, use **Webhook deliveries -> Resend**, or call the status API for open orders.

## 7. Test, then go live
Test mode: PayU test card `5123456789012346`, CVV `123`, any future expiry, OTP `123456`; UPI `anything@payu`. Try: success, failure/cancel, closing the PayU page, a duplicate webhook, your webhook endpoint returning 500 (watch retries), a partial refund.
Going live needs the platform side to enable LIVE for your business after KYC (not self-service); then create a LIVE key (`sk_live_...`) and switch your configuration. Keep test and live secrets separate.

## Run the example
```
# stack running (scripts/dev-start.sh), a TEST key and a webhook endpoint http://localhost:4000/apg-webhook created in the dashboard
APG_API_KEY=sk_test_... APG_WEBHOOK_SECRET=whsec_... node examples/sample-shop/server.js    # then open http://localhost:4000
```
