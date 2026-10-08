import { useState } from 'react'
import { api, type Order, type Page } from '../api'
import { Badge, Empty, ErrorNote, Modal, PageHead, Spinner, useLoad } from '../components'
import { dt, money, short } from '../util'

const SIZE = 15

export default function Orders() {
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState<Order | null>(null)
  const { data, error, loading, reload } = useLoad(() => api<Page<Order>>('GET', `/api/v1/orders?page=${page}&size=${SIZE}`), [page])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  return (
    <>
      <PageHead title="Orders" sub="What your customers are buying.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="🧾" text="No orders yet." /> : (
          <table>
            <thead><tr><th>Order</th><th>Reference</th><th>Customer</th><th>Amount</th><th>Status</th><th>Created</th></tr></thead>
            <tbody>
              {data.content.map((o) => (
                <tr key={o.id} className="click" onClick={() => setOpen(o)}>
                  <td className="mono">{short(o.id)}</td>
                  <td>{o.merchantOrderRef ?? <span className="muted">—</span>}</td>
                  <td>{o.customer?.name ?? <span className="muted">—</span>}</td>
                  <td><b>{money(o.amount, o.currency)}</b></td>
                  <td><Badge status={o.status} /></td>
                  <td className="muted">{dt(o.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} orders · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
      {open && (
        <Modal title={`Order ${short(open.id)}`} onClose={() => setOpen(null)}>
          <dl className="kv">
            <dt>Amount</dt><dd><b>{money(open.amount, open.currency)}</b></dd>
            <dt>Status</dt><dd><Badge status={open.status} /></dd>
            <dt>Reference</dt><dd>{open.merchantOrderRef ?? '—'}</dd>
            <dt>Customer</dt><dd>{open.customer?.name ?? '—'}<div className="muted">{open.customer?.email} {open.customer?.phone}</div></dd>
            <dt>Description</dt><dd>{open.description ?? '—'}</dd>
            <dt>Created</dt><dd>{dt(open.createdAt)}</dd>
            <dt>Order id</dt><dd className="mono">{open.id}</dd>
          </dl>
          {open.items && open.items.length > 0 && (
            <>
              <h2 style={{ marginTop: 18 }}>Items</h2>
              {open.items.map((it, i) => (
                <div key={i} className="row"><span className="grow">{it.name} × {it.quantity}</span><b>{money(it.unitAmount * it.quantity, open.currency)}</b></div>
              ))}
            </>
          )}
        </Modal>
      )}
    </>
  )
}
