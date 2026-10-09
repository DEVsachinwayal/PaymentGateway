// Quick local load check against the gateway (LOCAL ONLY, see ../README.md safety notes: no checkout/verify, nothing reaches PayU).
// Usage: node quick-load.js [connections=20] [seconds=20]   needs ../data/credentials.csv (performance/setup-data.sh)
const autocannon = require('autocannon')
const fs = require('fs')
const crypto = require('crypto')
const path = require('path')

const connections = Number(process.argv[2] || 20)
const duration = Number(process.argv[3] || 20)
const base = process.env.GATEWAY_URL || 'http://localhost:8080'
const [, line] = fs.readFileSync(path.join(__dirname, '..', 'data', 'credentials.csv'), 'utf8').trim().split(/\r?\n/)
const [apiKey, email, password] = line.split(',')
const uuid = () => crypto.randomUUID()
const auth = { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json' }

const scenarios = {
  'create order': {
    requests: [{
      method: 'POST', path: '/api/v1/orders',
      setupRequest: (req) => ({ ...req, headers: { ...auth, 'idempotency-key': 'o-' + uuid() }, body: JSON.stringify({ amount: 100 + Math.floor(Math.random() * 99900), currency: 'INR', description: 'load' }) }),
    }],
    expect: 201,
  },
  'create order + payment': {
    requests: [
      { method: 'POST', path: '/api/v1/orders',
        setupRequest: (req) => ({ ...req, headers: { ...auth, 'idempotency-key': 'po-' + uuid() }, body: JSON.stringify({ amount: 5000, currency: 'INR' }) }),
        onResponse: (status, body, context) => { if (status === 201) context.orderId = JSON.parse(body).data.id } },
      { method: 'POST', path: '/api/v1/payments',
        setupRequest: (req, context) => ({ ...req, headers: { ...auth, 'idempotency-key': 'p-' + uuid() }, body: JSON.stringify({ orderId: context.orderId }) }) },
    ],
    expect: 201,
  },
  'list orders (read)': { requests: [{ method: 'GET', path: '/api/v1/orders?size=20', headers: auth }], expect: 200 },
  'list payments (read)': { requests: [{ method: 'GET', path: '/api/v1/payments?size=20', headers: auth }], expect: 200 },
  'forged payu callback (rejected)': {
    requests: [{
      method: 'POST', path: '/api/v1/webhooks/payu',
      setupRequest: (req) => ({ ...req, headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'text/html' },
        body: `txnid=A${crypto.randomBytes(6).toString('hex')}&mihpayid=1&status=success&unmappedstatus=captured&amount=1.00&key=forged&hash=${crypto.randomBytes(64).toString('hex')}` }),
    }],
    expect: 400,
  },
}

async function login() {
  const t0 = Date.now()
  const res = await fetch(base + '/api/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password }) })
  return { status: res.status, ms: Date.now() - t0, jwt: res.ok ? (await res.json()).data.accessToken : null }
}

;(async () => {
  const rows = []
  for (const [name, s] of Object.entries(scenarios)) {
    const r = await autocannon({ url: base, connections, duration, requests: s.requests, headers: s.requests[0].headers })
    const ok = r[`${s.expect}xx`] !== undefined ? 0 : 0
    const non = r.non2xx
    rows.push({ scenario: name, rps: Math.round(r.requests.average), p50: r.latency.p50, p95: r.latency.p97_5, p99: r.latency.p99, max: r.latency.max,
      total: r.requests.total, errors: r.errors, timeouts: r.timeouts, non2xx: non, expected: s.expect, statuses: Object.keys(r).filter((k) => /^\d00$|^\dxx$/.test(k)).map((k) => k + ':' + r[k]).join(' ') })
    console.log(name, '->', JSON.stringify(rows[rows.length - 1]))
  }
  const l = await login()
  console.log('single login (bcrypt) ms:', l.ms, 'status', l.status)
  if (l.jwt) {
    const r = await autocannon({ url: base, connections: Math.min(connections, 10), duration: Math.min(duration, 10), requests: [{ method: 'GET', path: '/api/v1/ledger/balance', headers: { authorization: 'Bearer ' + l.jwt } }] })
    console.log('ledger balance (JWT) ->', JSON.stringify({ rps: Math.round(r.requests.average), p50: r.latency.p50, p99: r.latency.p99, non2xx: r.non2xx, errors: r.errors }))
  }
  fs.writeFileSync(path.join(__dirname, '..', 'results-quick.json'), JSON.stringify({ at: new Date().toISOString(), connections, duration, rows }, null, 2))
})().catch((e) => { console.error(e); process.exit(1) })
