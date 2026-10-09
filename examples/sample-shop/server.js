// A tiny example "shop" that accepts payments through APG. No dependencies: `node server.js`.
// It shows the whole merchant integration (docs/INTEGRATION_GUIDE.md):
//   POST /buy            create order -> payment -> checkout on APG with the SECRET API key (server side only), redirect the customer
//   GET  /thanks         where the customer lands afterwards: never trusted, only shows the order and refreshes
//   POST /apg-webhook    APG's signed event: THE place where an order becomes "paid" (signature + age + duplicate checks)
// Orders are kept in memory (restart = empty). Configure with environment variables:
//   APG_URL (default http://localhost:8080)   APG_API_KEY (sk_test_...)   APG_WEBHOOK_SECRET (whsec_... of your endpoint)
//   PORT (default 4000)   RETURN_URL (optional https URL on your site; APG's own result page is used when unset)
const http = require('http')
const crypto = require('crypto')

const APG_URL = process.env.APG_URL || 'http://localhost:8080'
const API_KEY = process.env.APG_API_KEY || ''
const WEBHOOK_SECRET = process.env.APG_WEBHOOK_SECRET || ''
const PORT = Number(process.env.PORT || 4000)
const RETURN_URL = process.env.RETURN_URL || ''

const PRODUCTS = { book: { name: 'Learn Payments (book)', paise: 49900 }, pen: { name: 'Fountain pen', paise: 15000 } }
const orders = new Map()            // our order id -> { id, product, paise, status, apgPaymentId }
const seenEvents = new Set()        // webhook event ids we already handled (APG delivers at least once)

const html = (body) => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sample shop</title>
<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;color:#1b1f24}button{padding:10px 18px;border:0;border-radius:8px;background:#4f46e5;color:#fff;font-size:15px;cursor:pointer}
.card{border:1px solid #ddd;border-radius:12px;padding:16px;margin:12px 0}.ok{color:#047857}.warn{color:#b45309}code{background:#f3f4f6;padding:2px 6px;border-radius:4px}</style>${body}`
const rupees = (p) => '₹' + (p / 100).toFixed(2)
const send = (res, code, body, type = 'text/html; charset=utf-8', headers = {}) => { res.writeHead(code, { 'Content-Type': type, ...headers }); res.end(body) }
const readBody = (req) => new Promise((ok, no) => { const c = []; req.on('data', (d) => { c.push(d); if (Buffer.concat(c).length > 1e6) { req.destroy(); no(new Error('too large')) } }); req.on('end', () => ok(Buffer.concat(c))) })

async function apg(method, path, body, idemKey) {
  const res = await fetch(APG_URL + path, {
    method,
    headers: { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json', ...(idemKey ? { 'Idempotency-Key': idemKey } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok || !json.success) throw new Error(`APG ${method} ${path} -> ${res.status} ${json?.error?.code || ''} ${json?.error?.message || ''}`)
  return json.data
}

/** Verifies "X-APG-Signature: t=<unix>,v1=<hex>" = HMAC-SHA256(secret, t + "." + rawBody) and rejects old timestamps. */
function validSignature(header, raw) {
  if (!WEBHOOK_SECRET || !header) return false
  const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')))
  if (!parts.t || !parts.v1) return false
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return false                   // replayed or very old
  const expected = crypto.createHmac('sha256', WEBHOOK_SECRET).update(parts.t + '.' + raw.toString('utf8')).digest('hex')
  const a = Buffer.from(expected), b = Buffer.from(parts.v1)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x')
    if (req.method === 'GET' && url.pathname === '/') {
      const items = Object.entries(PRODUCTS).map(([id, p]) => `<div class="card"><b>${p.name}</b> · ${rupees(p.paise)}
        <form method="post" action="/buy" style="display:inline;float:right"><input type="hidden" name="product" value="${id}"><button>Buy with APG</button></form></div>`).join('')
      const missing = API_KEY ? '' : '<p class="warn">Set APG_API_KEY (a TEST key from the dashboard) before buying.</p>'
      return send(res, 200, html(`<h1>Sample shop</h1>${missing}${items}<p><a href="/orders">My orders</a></p>`))
    }
    if (req.method === 'POST' && url.pathname === '/buy') {
      const form = new URLSearchParams((await readBody(req)).toString())
      const product = PRODUCTS[form.get('product')]
      if (!product) return send(res, 400, html('<p>Unknown product</p>'))
      const id = 'ORDER-' + crypto.randomBytes(4).toString('hex').toUpperCase()
      const order = { id, product: product.name, paise: product.paise, status: 'WAITING_FOR_PAYMENT', apgPaymentId: null }
      orders.set(id, order)
      // one idempotency key per step and order: a retry after a network error can never create a second order or payment
      const o = await apg('POST', '/api/v1/orders', { amount: product.paise, currency: 'INR', merchantOrderRef: id, description: product.name }, `order-${id}`)
      const p = await apg('POST', '/api/v1/payments', { orderId: o.id, ...(RETURN_URL ? { returnUrl: RETURN_URL } : {}) }, `payment-${id}`)
      order.apgPaymentId = p.id
      const c = await apg('POST', `/api/v1/payments/${p.id}/checkout`, {}, `checkout-${id}`)
      return send(res, 303, '', 'text/plain', { Location: c.checkoutUrl })            // the customer pays on the provider's hosted page
    }
    if (req.method === 'GET' && (url.pathname === '/thanks' || url.pathname === '/orders')) {
      const rows = [...orders.values()].reverse().map((o) => `<div class="card"><b>${o.id}</b> · ${o.product} · ${rupees(o.paise)}<br>
        ${o.status === 'PAID' ? '<span class="ok">PAID ✓</span>' : o.status === 'FAILED' ? '<span class="warn">Payment failed</span>' : '<span class="warn">Waiting for confirmation…</span>'}</div>`).join('')
      const refresh = [...orders.values()].some((o) => o.status === 'WAITING_FOR_PAYMENT') ? '<meta http-equiv="refresh" content="3">' : ''
      return send(res, 200, html(`${refresh}<h1>Your orders</h1><p class="warn">Status is updated only by APG's signed webhook, not by this page.</p>${rows || '<p>No orders yet.</p>'}<p><a href="/">Back to shop</a></p>`))
    }
    if (req.method === 'POST' && url.pathname === '/apg-webhook') {
      const raw = await readBody(req)
      if (!validSignature(req.headers['x-apg-signature'], raw)) return send(res, 400, 'bad signature', 'text/plain')
      const event = JSON.parse(raw.toString('utf8'))
      if (seenEvents.has(event.id)) return send(res, 200, 'duplicate', 'text/plain')    // already handled: still answer 2xx
      seenEvents.add(event.id)
      const order = [...orders.values()].find((o) => o.apgPaymentId === event.data.paymentId)
      if (order && event.type === 'payment.success' && event.data.amount === order.paise) order.status = 'PAID'   // amount must match what we asked for
      if (order && event.type === 'payment.failed') order.status = 'FAILED'
      console.log('webhook', event.type, event.id, order ? order.id + ' -> ' + order.status : '(unknown payment)')
      return send(res, 200, 'ok', 'text/plain')                                         // 2xx tells APG not to retry
    }
    send(res, 404, html('<p>Not found</p>'))
  } catch (e) {
    console.error(e.message)
    send(res, 500, html(`<p class="warn">Something went wrong: ${String(e.message).replace(/[<>&]/g, '')}</p><p><a href="/">Back</a></p>`))
  }
}).listen(PORT, () => console.log(`sample shop on http://localhost:${PORT} (APG at ${APG_URL})`))
