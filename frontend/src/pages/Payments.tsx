import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type Page, type Payment } from '../api'
import { Badge, Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt, money, short } from '../util'

const SIZE = 15
const FILTERS = ['ALL', 'SUCCESS', 'FAILED', 'PENDING', 'PROCESSING', 'CREATED']

export default function Payments() {
  const nav = useNavigate()
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('ALL')
  const { data, error, loading, reload } = useLoad(() => api<Page<Payment>>('GET', `/api/v1/payments?page=${page}&size=${SIZE}`), [page])
  const rows = (data?.content ?? []).filter((p) => filter === 'ALL' || p.status === filter)
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  return (
    <>
      <PageHead title="Payments" sub="Every payment attempt and its verified status.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="pill-toggle" style={{ marginBottom: 14 }}>
        {FILTERS.map((f) => <button key={f} className={f === filter ? 'on' : ''} onClick={() => setFilter(f)}>{f === 'ALL' ? 'All' : f[0] + f.slice(1).toLowerCase()}</button>)}
      </div>
      <div className="card flush">
        {loading ? <Spinner /> : rows.length === 0 ? <Empty icon="💳" text="No payments to show." /> : (
          <table>
            <thead><tr><th>Payment</th><th>Amount</th><th>Method</th><th>Status</th><th>Created</th></tr></thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id} className="click" onClick={() => nav(`/payments/${p.id}`)}>
                  <td className="mono">{short(p.id)}</td>
                  <td><b>{money(p.amount, p.currency)}</b></td>
                  <td>{p.paymentMethod ?? <span className="muted">chosen at checkout</span>}</td>
                  <td><Badge status={p.status} /></td>
                  <td className="muted">{dt(p.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} payments · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
    </>
  )
}
