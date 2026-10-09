import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, newKey, toMinor, type Order, type Payment } from '../api'
import { useAuth } from '../auth'
import { Badge, Copy, ErrorNote, PageHead } from '../components'
import { money } from '../util'

interface Started { payment: Payment; url: string }
const FINAL = ['SUCCESS', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED']

/** Collect a payment from the dashboard: creates the order + payment + hosted checkout link and lets you open it. */
export default function NewPayment() {
  const { merchant } = useAuth()
  const [amount, setAmount] = useState('100')
  const [description, setDescription] = useState('')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [started, setStarted] = useState<Started | null>(null)
  const [status, setStatus] = useState<string>('CREATED')
  // idempotency keys live as long as the form values do: a double click or retry can never create a second order
  const keys = useRef({ order: newKey(), pay: newKey(), checkout: newKey() })

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const minor = toMinor(amount)
    if (minor === null) { setError(new Error('Enter an amount like 250 or 250.50')); return }
    setBusy(true)
    try {
      const order = await api<Order>('POST', '/api/v1/orders', {
        amount: minor, currency: merchant?.defaultCurrency ?? 'INR',
        ...(description.trim() ? { description: description.trim() } : {}),
        ...((name.trim() || email.trim() || phone.trim()) ? { customer: { ...(name.trim() ? { name: name.trim() } : {}), ...(email.trim() ? { email: email.trim() } : {}), ...(phone.trim() ? { phone: phone.trim() } : {}) } } : {}),
      }, { 'Idempotency-Key': keys.current.order })
      const payment = await api<Payment>('POST', '/api/v1/payments', { orderId: order.id }, { 'Idempotency-Key': keys.current.pay })
      const co = await api<{ checkoutUrl: string }>('POST', `/api/v1/payments/${payment.id}/checkout`, {}, { 'Idempotency-Key': keys.current.checkout })
      setStarted({ payment, url: co.checkoutUrl })
      setStatus(payment.status)
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  function reset() {
    keys.current = { order: newKey(), pay: newKey(), checkout: newKey() }
    setStarted(null); setStatus('CREATED'); setError(null)
  }

  // follow the payment after the link is opened (the server decides the status, not this page)
  useEffect(() => {
    if (!started || FINAL.includes(status)) return
    const t = setInterval(async () => {
      try { setStatus((await api<Payment>('GET', `/api/v1/payments/${started.payment.id}`)).status) } catch { /* keep trying */ }
    }, 3000)
    return () => clearInterval(t)
  }, [started, status])

  return (
    <>
      <PageHead title="Collect a payment" sub="Create a payment and open its checkout page." />
      {merchant?.status !== 'ACTIVE' && <div className="alert warn">Test mode: use PayU test cards. No real money moves.</div>}
      {!started ? (
        <form className="card" style={{ maxWidth: 560 }} onSubmit={submit}>
          <ErrorNote error={error} />
          <div className="field"><label htmlFor="amt">Amount ({merchant?.defaultCurrency ?? 'INR'})</label>
            <input id="amt" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required /></div>
          <div className="field"><label htmlFor="d">Description <span className="muted">(optional)</span></label>
            <input id="d" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} placeholder="e.g. Order #1042" /></div>
          <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="field"><label htmlFor="n">Customer name</label><input id="n" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></div>
            <div className="field"><label htmlFor="p">Customer phone</label><input id="p" value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={20} /></div>
          </div>
          <div className="field"><label htmlFor="e">Customer email</label><input id="e" type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={254} /></div>
          <button className="btn primary" disabled={busy}>{busy ? 'Creating…' : 'Create payment link'}</button>
        </form>
      ) : (
        <div className="card" style={{ maxWidth: 640 }}>
          <div className="row" style={{ marginBottom: 12 }}>
            <h2 className="grow" style={{ margin: 0 }}>{money(started.payment.amount, started.payment.currency)}</h2>
            <Badge status={status} />
          </div>
          <p className="muted">Open the checkout page to pay (or send the link to the customer; it works for 30 minutes). The status below updates by itself.</p>
          <div className="secret" style={{ marginBottom: 12 }}><code>{started.url}</code><Copy text={started.url} /></div>
          <div className="row" style={{ flexWrap: 'wrap' }}>
            <a className="btn primary" href={started.url} target="_blank" rel="noreferrer">Open payment page ↗</a>
            <Link className="btn" to={`/payments/${started.payment.id}`}>View payment</Link>
            <button className="btn" onClick={reset}>Another payment</button>
          </div>
          {status === 'SUCCESS' && <div className="alert ok" style={{ marginTop: 14 }}>Payment received and confirmed by the provider.</div>}
          {status === 'FAILED' && <div className="alert err" style={{ marginTop: 14 }}>The payment failed. You can start a new one.</div>}
        </div>
      )}
    </>
  )
}
