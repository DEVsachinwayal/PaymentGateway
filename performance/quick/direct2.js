const autocannon = require('autocannon'), crypto = require('crypto')
const h = { 'x-internal-token': process.env.INTERNAL_SERVICE_TOKEN, 'x-merchant-id': process.env.MID, 'x-auth-type': 'API_KEY', 'content-type': 'application/json' }
const run = async (name, url, req) => {
  const r = await autocannon({ url, connections: 20, duration: 10, requests: [req] })
  console.log(name.padEnd(36), 'rps', Math.round(r.requests.average), 'p50', r.latency.p50, 'p95', r.latency.p97_5, 'p99', r.latency.p99, 'non2xx', r.non2xx, 'err', r.errors)
}
;(async () => {
  const post = (path) => ({ method: 'POST', path, setupRequest: (req) => ({ ...req, headers: { ...h, 'idempotency-key': 'd-' + crypto.randomUUID() }, body: JSON.stringify({ amount: 2500, currency: 'INR' }) }) })
  await run('warmup', 'http://localhost:8083', post('/api/v1/orders'))
  await run('order-service direct: create order', 'http://localhost:8083', post('/api/v1/orders'))
  await run('order-service direct: list', 'http://localhost:8083', { method: 'GET', path: '/api/v1/orders?size=20', headers: h })
})()
