import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Page, type Refund } from '../api'
import { Badge, Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt, money, short } from '../util'

const SIZE = 15

export default function Refunds() {
  const [page, setPage] = useState(0)
  const { data, error, loading, reload } = useLoad(() => api<Page<Refund>>('GET', `/api/v1/refunds?page=${page}&size=${SIZE}`), [page])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  return (
    <>
      <PageHead title="Refunds" sub="Money returned to customers. Start a refund from a payment's page.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="↩️" text="No refunds yet." /> : (
          <table>
            <thead><tr><th>Refund</th><th>Payment</th><th>Amount</th><th>Status</th><th>Created</th></tr></thead>
            <tbody>
              {data.content.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{short(r.id)}{r.reason && <div className="muted" style={{ fontSize: 12 }}>{r.reason}</div>}</td>
                  <td><Link to={`/payments/${r.paymentId}`} className="mono">{short(r.paymentId)}</Link></td>
                  <td><b>{money(r.amount, r.currency)}</b></td>
                  <td><Badge status={r.status} />{r.failureMessage && <div className="muted" style={{ fontSize: 12 }}>{r.failureMessage}</div>}
                    {r.status === 'VERIFY_REQUIRED' && <div className="muted" style={{ fontSize: 12 }}>Being checked by our team</div>}</td>
                  <td className="muted">{dt(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} refunds · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
    </>
  )
}
