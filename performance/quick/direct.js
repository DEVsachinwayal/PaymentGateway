const autocannon = require('autocannon')
const run = async (name, url, headers, c = 20, d = 10) => {
  const r = await autocannon({ url, connections: c, duration: d, headers })
  console.log(name.padEnd(34), 'rps', Math.round(r.requests.average), 'p50', r.latency.p50, 'p99', r.latency.p99, 'non2xx', r.non2xx, 'err', r.errors)
}
;(async () => {
  const h = { 'x-internal-token': process.env.INTERNAL_SERVICE_TOKEN, 'x-merchant-id': process.env.MID, 'x-auth-type': 'API_KEY' }
  await run('warmup order-service direct', 'http://localhost:8083/api/v1/orders?size=20', h, 10, 5)
  await run('order-service direct (list)', 'http://localhost:8083/api/v1/orders?size=20', h)
  await run('payment-service direct (list)', 'http://localhost:8084/api/v1/payments?size=20', h)
  await run('merchant verify direct', 'http://localhost:8082/actuator/health', {})
  const verify = await autocannon({ url: 'http://localhost:8082/internal/v1/api-keys/verify', connections: 20, duration: 10, method: 'POST',
    headers: { 'x-internal-token': process.env.INTERNAL_SERVICE_TOKEN, 'content-type': 'application/json' }, body: JSON.stringify({ apiKey: process.env.APIKEY }) })
  console.log('api-key verify (merchant-service)'.padEnd(34), 'rps', Math.round(verify.requests.average), 'p50', verify.latency.p50, 'p99', verify.latency.p99, 'non2xx', verify.non2xx)
})()
