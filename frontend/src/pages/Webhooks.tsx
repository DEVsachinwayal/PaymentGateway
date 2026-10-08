import { useState, type FormEvent } from 'react'
import { api, type Webhook } from '../api'
import { Badge, Empty, ErrorNote, Modal, OneTimeSecret, PageHead, Spinner, useLoad } from '../components'
import { dt } from '../util'

const EVENTS = ['payment.success', 'payment.failed', 'payment.unknown', 'refund.success', 'refund.failed', 'settlement.created']

export default function Webhooks() {
  const { data, error, loading, reload } = useLoad(() => api<Webhook[]>('GET', '/api/v1/webhook-endpoints'))
  const [creating, setCreating] = useState(false)
  const [url, setUrl] = useState('')
  const [events, setEvents] = useState<string[]>(['payment.success', 'payment.failed'])
  const [fresh, setFresh] = useState<Webhook | null>(null)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setFormError(null)
    try {
      const w = await api<Webhook>('POST', '/api/v1/webhook-endpoints', { url, events })
      setFresh(w); setCreating(false); setUrl(''); reload()
    } catch (err) { setFormError(err) } finally { setBusy(false) }
  }
  async function remove(w: Webhook) {
    if (!confirm(`Delete the endpoint ${w.url}?`)) return
    try { await api('DELETE', `/api/v1/webhook-endpoints/${w.id}`); reload() }
    catch (err) { alert(err instanceof Error ? err.message : 'Failed') }
  }
  const toggle = (ev: string) => setEvents((cur) => (cur.includes(ev) ? cur.filter((x) => x !== ev) : [...cur, ev]))

  return (
    <>
      <PageHead title="Webhooks" sub="We call your server when a payment changes state. Every call is signed.">
        <button className="btn primary" onClick={() => { setFormError(null); setCreating(true) }}>+ Add endpoint</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.length ? <Empty icon="🔔" text="No endpoints yet. Add an https URL to receive payment events." /> : (
          <table>
            <thead><tr><th>Endpoint</th><th>Events</th><th>Status</th><th /></tr></thead>
            <tbody>
              {data.map((w) => (
                <tr key={w.id}>
                  <td><b className="mono">{w.url}</b><div className="muted" style={{ fontSize: 12 }}>Added {dt(w.createdAt)}</div></td>
                  <td>{w.events.map((e) => <span key={e} className="badge b-mute" style={{ margin: '2px 4px 2px 0' }}>{e}</span>)}</td>
                  <td><Badge status={w.status} /></td>
                  <td className="right"><button className="btn sm danger" onClick={() => remove(w)}>Delete</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <p className="muted">Delivery history will appear here once outbound merchant webhooks are built.</p>

      {creating && (
        <Modal title="Add webhook endpoint" onClose={() => setCreating(false)}>
          <form onSubmit={create}>
            <ErrorNote error={formError} />
            <div className="field"><label htmlFor="wu">Endpoint URL (https)</label>
              <input id="wu" type="url" value={url} onChange={(e) => setUrl(e.target.value)} required placeholder="https://example.com/apg-webhook" pattern="https://.*" /></div>
            <div className="field"><label>Events</label>
              {EVENTS.map((ev) => (
                <label key={ev} style={{ fontWeight: 400, display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input type="checkbox" style={{ width: 'auto' }} checked={events.includes(ev)} onChange={() => toggle(ev)} />{ev}
                </label>
              ))}
            </div>
            <button className="btn primary" disabled={busy || events.length === 0}>{busy ? 'Adding…' : 'Add endpoint'}</button>
          </form>
        </Modal>
      )}
      {fresh?.signingSecret && (
        <Modal title="Signing secret" onClose={() => setFresh(null)}>
          <OneTimeSecret value={fresh.signingSecret} note="Use this to verify the signature on each webhook. It is shown only once." />
          <button className="btn primary" style={{ marginTop: 14 }} onClick={() => setFresh(null)}>I have saved it</button>
        </Modal>
      )}
    </>
  )
}
