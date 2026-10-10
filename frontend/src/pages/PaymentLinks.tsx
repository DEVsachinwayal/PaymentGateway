import { useState, type FormEvent } from 'react'
import { api, newKey, toMinor, type Page, type PaymentLink } from '../api'
import { useAuth } from '../auth'
import { Badge, Copy, Empty, ErrorNote, Modal, PageHead, Spinner, useLoad } from '../components'
import { dt, money } from '../util'

const SIZE = 15
const FILTERS = ['ALL', 'ACTIVE', 'PAID', 'EXPIRED', 'CANCELLED']

export default function PaymentLinks() {
  const { merchant } = useAuth()
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('ALL')
  const q = filter === 'ALL' ? '' : `&status=${filter}`
  const { data, error, loading, reload } = useLoad(() => api<Page<PaymentLink>>('GET', `/api/v1/payment-links?page=${page}&size=${SIZE}${q}`), [page, filter])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<PaymentLink | null>(null)

  async function cancel(l: PaymentLink) {
    if (!confirm('Cancel this link? Customers will no longer be able to pay it.')) return
    try { await api('POST', `/api/v1/payment-links/${l.id}/cancel`); reload() } catch (e) { alert(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <>
      <PageHead title="Payment links" sub="Share a link; the customer opens it and pays. No website needed.">
        <button className="btn primary" onClick={() => setCreating(true)}>+ Create link</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="pill-toggle" style={{ marginBottom: 14 }}>
        {FILTERS.map((f) => <button key={f} className={f === filter ? 'on' : ''} onClick={() => { setFilter(f); setPage(0) }}>{f === 'ALL' ? 'All' : f[0] + f.slice(1).toLowerCase()}</button>)}
      </div>
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="🔗" text="No payment links yet. Create one and send it on WhatsApp, SMS or email." /> : (
          <table>
            <thead><tr><th>Link</th><th>Amount</th><th>Status</th><th>Paid</th><th>Expires</th><th /></tr></thead>
            <tbody>
              {data.content.map((l) => (
                <tr key={l.id}>
                  <td><b>{l.description ?? 'Payment'}</b><div className="mono muted" style={{ fontSize: 12 }}>…/pay/{l.code} · {l.oneTime ? 'one-time' : 'reusable'}</div></td>
                  <td><b>{money(l.amount, l.currency)}</b></td>
                  <td><Badge status={l.status} /></td>
                  <td>{l.successfulPayments > 0 ? `${l.successfulPayments}×` : '—'}</td>
                  <td className="muted">{dt(l.expiresAt)}</td>
                  <td className="right"><span className="row" style={{ justifyContent: 'flex-end' }}>
                    <Copy text={l.url} label="Copy link" />
                    {l.status === 'ACTIVE' && <><a className="btn sm" href={l.url} target="_blank" rel="noreferrer">Open ↗</a><button className="btn sm danger" onClick={() => cancel(l)}>Cancel</button></>}
                  </span></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} links · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
      {merchant?.status !== 'ACTIVE' && <p className="muted">Test mode: links work with PayU test cards and no real money moves.</p>}

      {creating && <CreateModal currency={merchant?.defaultCurrency ?? 'INR'} onClose={() => setCreating(false)} onDone={(l) => { setCreating(false); setCreated(l); reload() }} />}
      {created && (
        <Modal title="Your payment link" onClose={() => setCreated(null)}>
          <div className="secret" style={{ marginBottom: 12 }}><code>{created.url}</code><Copy text={created.url} /></div>
          <p className="muted">Send it to your customer. It shows your business name and the amount, and the payment happens on the provider's secure page.
            {created.oneTime ? ' It stops working after the first successful payment.' : ' It can be paid many times until it expires.'}</p>
          <div className="row"><a className="btn primary" href={created.url} target="_blank" rel="noreferrer">Open link ↗</a><button className="btn" onClick={() => setCreated(null)}>Done</button></div>
        </Modal>
      )}
    </>
  )
}

function CreateModal({ currency, onClose, onDone }: { currency: string; onClose: () => void; onDone: (l: PaymentLink) => void }) {
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [days, setDays] = useState('7')
  const [oneTime, setOneTime] = useState(true)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [key] = useState(newKey)       // one key per open dialog: a double click cannot create two links

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const minor = toMinor(amount)
    if (minor === null) { setError(new Error('Enter an amount like 250 or 250.50')); return }
    const d = Number(days)
    if (!Number.isInteger(d) || d < 1 || d > 90) { setError(new Error('Expiry must be between 1 and 90 days')); return }
    setBusy(true)
    try {
      const l = await api<PaymentLink>('POST', '/api/v1/payment-links', {
        amount: minor, currency, oneTime, expiresInHours: d * 24,
        ...(description.trim() ? { description: description.trim() } : {}),
        ...((name.trim() || email.trim() || phone.trim()) ? { customer: { ...(name.trim() ? { name: name.trim() } : {}), ...(email.trim() ? { email: email.trim() } : {}), ...(phone.trim() ? { phone: phone.trim() } : {}) } } : {}),
      }, { 'Idempotency-Key': key })
      onDone(l)
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  return (
    <Modal title="Create payment link" onClose={onClose}>
      <form onSubmit={submit}>
        <ErrorNote error={error} />
        <div className="field"><label htmlFor="la">Amount ({currency})</label><input id="la" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required placeholder="499" /></div>
        <div className="field"><label htmlFor="ld">What is it for?</label><input id="ld" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} placeholder="e.g. Invoice #1042" /></div>
        <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div className="field"><label htmlFor="lx">Valid for (days)</label><input id="lx" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} /></div>
          <div className="field"><label>Use</label>
            <div className="pill-toggle"><button type="button" className={oneTime ? 'on' : ''} onClick={() => setOneTime(true)}>Once</button><button type="button" className={!oneTime ? 'on' : ''} onClick={() => setOneTime(false)}>Many times</button></div></div>
        </div>
        <details style={{ marginBottom: 14 }}><summary className="muted" style={{ cursor: 'pointer' }}>Customer details (optional)</summary>
          <div className="field" style={{ marginTop: 10 }}><label htmlFor="ln">Name</label><input id="ln" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></div>
          <div className="field"><label htmlFor="le">Email</label><input id="le" type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={254} /></div>
          <div className="field"><label htmlFor="lp">Phone</label><input id="lp" value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={20} /></div>
        </details>
        <button className="btn primary" disabled={busy}>{busy ? 'Creating…' : 'Create link'}</button>
      </form>
    </Modal>
  )
}
