import { useState } from 'react'
import { api, type Delivery, type Page } from '../api'
import { Badge, Empty, ErrorNote, Modal, PageHead, Spinner, useLoad } from '../components'
import { dt, short } from '../util'

const SIZE = 15
const FILTERS = ['ALL', 'DELIVERED', 'RETRYING', 'DEAD', 'CANCELLED']

export default function Deliveries() {
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('ALL')
  const [open, setOpen] = useState<Delivery | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  const q = filter === 'ALL' ? '' : `&status=${filter}`
  const { data, error, loading, reload } = useLoad(() => api<Page<Delivery>>('GET', `/api/v1/webhook-deliveries?page=${page}&size=${SIZE}${q}`), [page, filter])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  async function details(d: Delivery) {
    setActionError(null)
    setOpen(await api<Delivery>('GET', `/api/v1/webhook-deliveries/${d.id}`))
  }
  async function resend(d: Delivery) {
    setBusy(true); setActionError(null)
    try { setOpen(await api<Delivery>('POST', `/api/v1/webhook-deliveries/${d.id}/resend`)); reload() }
    catch (e) { setActionError(e) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHead title="Webhook deliveries" sub="Every notification we sent (or tried to send) to your endpoints.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="pill-toggle" style={{ marginBottom: 14 }}>
        {FILTERS.map((f) => <button key={f} className={f === filter ? 'on' : ''} onClick={() => { setFilter(f); setPage(0) }}>{f === 'ALL' ? 'All' : f[0] + f.slice(1).toLowerCase()}</button>)}
      </div>
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="🔔" text="No deliveries to show." /> : (
          <table>
            <thead><tr><th>Event</th><th>Endpoint</th><th>Status</th><th>Tries</th><th>When</th></tr></thead>
            <tbody>
              {data.content.map((d) => (
                <tr key={d.id} className="click" onClick={() => details(d)}>
                  <td><b>{d.eventType}</b><div className="mono muted" style={{ fontSize: 12 }}>{short(d.eventId)}</div></td>
                  <td className="mono" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.endpointUrl}</td>
                  <td><Badge status={d.status} />{d.lastError && <div className="muted" style={{ fontSize: 12 }}>{d.lastError}</div>}</td>
                  <td>{d.attempts}</td>
                  <td className="muted">{dt(d.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} deliveries · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>

      {open && (
        <Modal title={`${open.eventType}`} onClose={() => setOpen(null)}>
          <ErrorNote error={actionError} />
          <dl className="kv">
            <dt>Status</dt><dd><Badge status={open.status} /></dd>
            <dt>Event id</dt><dd className="mono">{open.eventId}</dd>
            <dt>Endpoint</dt><dd className="mono">{open.endpointUrl}</dd>
            <dt>Tries</dt><dd>{open.attempts}{open.nextAttemptAt && <span className="muted"> · next try {dt(open.nextAttemptAt)}</span>}</dd>
          </dl>
          <h2 style={{ marginTop: 18 }}>Attempts</h2>
          {open.history.length === 0 ? <p className="muted">No attempt yet.</p> : (
            <table><tbody>
              {open.history.map((h) => (
                <tr key={h.attemptNo}><td>#{h.attemptNo}</td><td>{h.outcome}{h.httpStatus ? ` (${h.httpStatus})` : ''}</td><td className="muted">{h.detail ?? ''}</td><td className="muted right">{h.durationMs} ms · {dt(h.at)}</td></tr>
              ))}
            </tbody></table>
          )}
          {['DEAD', 'DELIVERED', 'CANCELLED'].includes(open.status) && (
            <p><button className="btn primary" disabled={busy} onClick={() => resend(open)}>{busy ? 'Sending…' : 'Resend'}</button>
              <span className="muted"> Same event id: your server should ignore a repeat it has already processed.</span></p>
          )}
        </Modal>
      )}
    </>
  )
}
