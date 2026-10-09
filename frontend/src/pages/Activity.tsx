import { useState } from 'react'
import { api, type AuditEvent } from '../api'
import { Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt } from '../util'

const SIZE = 25
const WORDS: Record<string, string> = {
  MERCHANT_CREATED: 'Business created', MERCHANT_SETTINGS_UPDATED: 'Settings changed', API_KEY_CREATED: 'API key created', API_KEY_ROTATED: 'API key rotated',
  API_KEY_REVOKED: 'API key revoked', WEBHOOK_ENDPOINT_CREATED: 'Webhook endpoint added', WEBHOOK_ENDPOINT_DELETED: 'Webhook endpoint removed',
  REFUND_CREATED: 'Refund requested', WEBHOOK_DELIVERY_RESEND: 'Webhook resent', PAYOUT_MARK_PAID: 'Payout marked paid',
}

export default function Activity() {
  const [page, setPage] = useState(0)
  const { data, error, loading, reload } = useLoad(() => api<{ content: AuditEvent[]; totalElements: number }>('GET', `/api/v1/audit-events?page=${page}&size=${SIZE}`), [page])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  return (
    <>
      <PageHead title="Activity log" sub="Who changed what in your account. Entries cannot be edited or deleted.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="📜" text="Nothing recorded yet." /> : (
          <table>
            <thead><tr><th>When</th><th>Action</th><th>By</th><th>Target</th></tr></thead>
            <tbody>
              {data.content.map((e) => (
                <tr key={e.id}>
                  <td className="muted">{dt(e.at)}</td>
                  <td><b>{WORDS[e.action] ?? e.action}</b></td>
                  <td className="mono" style={{ fontSize: 12 }}>{e.actor.startsWith('user:') ? 'dashboard user ' + e.actor.slice(5, 13) : e.actor.startsWith('api_key') ? 'API key' : e.actor}</td>
                  <td className="mono" style={{ fontSize: 12 }}>{e.target ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} events · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
      <p className="muted">Only account administrators can see this page.</p>
    </>
  )
}
