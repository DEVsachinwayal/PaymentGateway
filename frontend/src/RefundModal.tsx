import { useState, type FormEvent } from 'react'
import { api, newKey, toMinor, type Refund } from './api'
import { ErrorNote, Modal } from './components'
import { money } from './util'

/** Creates a refund. Amount is typed in rupees and converted to integer paise (invalid text is rejected, never rounded). */
export default function RefundModal({ paymentId, currency, maxMinor, onClose, onDone }: {
  paymentId: string; currency: string; maxMinor?: number; onClose: () => void; onDone: (r: Refund) => void
}) {
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  // one idempotency key per modal, so a double click or a retry after a timeout can never refund twice
  const [key] = useState(newKey)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const minor = amount.trim() === '' ? undefined : toMinor(amount)
    if (amount.trim() !== '' && minor === null) { setError(new Error('Enter an amount like 250 or 250.50')); return }
    setBusy(true)
    try {
      const r = await api<Refund>('POST', '/api/v1/refunds',
        { paymentId, ...(minor ? { amount: minor } : {}), ...(reason.trim() ? { reason: reason.trim() } : {}) }, { 'Idempotency-Key': key })
      onDone(r)
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  return (
    <Modal title="Refund payment" onClose={onClose}>
      <form onSubmit={submit}>
        <ErrorNote error={error} />
        <div className="field"><label htmlFor="ra">Amount ({currency}) <span className="muted">leave empty to refund everything that is left</span></label>
          <input id="ra" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder={maxMinor ? `up to ${money(maxMinor, currency)}` : ''} /></div>
        <div className="field"><label htmlFor="rr">Reason <span className="muted">(optional)</span></label>
          <input id="rr" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} /></div>
        <div className="alert warn">The refund is sent to the payment provider. It usually takes a few minutes to finish; the status updates by itself.</div>
        <button className="btn primary" disabled={busy}>{busy ? 'Sending…' : 'Refund'}</button>
      </form>
    </Modal>
  )
}
