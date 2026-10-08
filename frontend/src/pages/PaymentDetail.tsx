import { Link, useParams } from 'react-router-dom'
import { api, type Order, type Payment, type PaymentStatus } from '../api'
import { Badge, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt, money } from '../util'

export default function PaymentDetail() {
  const { id } = useParams()
  const { data, error, loading, reload } = useLoad(async () => {
    const [payment, status] = await Promise.all([
      api<Payment>('GET', `/api/v1/payments/${id}`),
      api<PaymentStatus>('GET', `/api/v1/payments/${id}/status`),
    ])
    const order = await api<Order>('GET', `/api/v1/orders/${payment.orderId}`).catch(() => null)
    return { payment, status, order }
  }, [id])

  if (loading) return <Spinner />
  if (!data) return <><PageHead title="Payment" /><ErrorNote error={error} /></>
  const { payment: p, status, order } = data

  return (
    <>
      <PageHead title={money(p.amount, p.currency)} sub={`Payment ${p.id}`}>
        <Badge status={p.status} />
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <p><Link to="/payments">← All payments</Link></p>
      <div className="grid g2">
        <div className="card">
          <h2>Timeline</h2>
          <ul className="timeline">
            {status.history.map((h, i) => (
              <li key={i} className={h.to === 'SUCCESS' ? 't-ok' : h.to === 'FAILED' ? 't-bad' : ''}>
                <div className="row"><b>{h.to.replace('_', ' ')}</b><span className="muted" style={{ marginLeft: 'auto' }}>{dt(h.at)}</span></div>
                <div className="muted" style={{ fontSize: 13 }}>
                  {h.from ? `from ${h.from.replace('_', ' ')} · ` : ''}via {h.source.toLowerCase()}{h.reason ? ` — ${h.reason}` : ''}
                </div>
              </li>
            ))}
            {status.history.length === 0 && <li className="muted">No history yet.</li>}
          </ul>
        </div>
        <div className="grid" style={{ alignContent: 'start' }}>
          <div className="card">
            <h2>Details</h2>
            <dl className="kv">
              <dt>Status</dt><dd><Badge status={p.status} /></dd>
              <dt>Method</dt><dd>{p.paymentMethod ?? 'Chosen at checkout'}</dd>
              <dt>Created</dt><dd>{dt(p.createdAt)}</dd>
              <dt>Updated</dt><dd>{dt(p.updatedAt)}</dd>
              <dt>Order</dt><dd className="mono">{p.orderId}</dd>
            </dl>
          </div>
          {order && (
            <div className="card">
              <h2>Order</h2>
              <dl className="kv">
                <dt>Reference</dt><dd>{order.merchantOrderRef ?? '—'}</dd>
                <dt>Customer</dt><dd>{order.customer?.name ?? '—'}<div className="muted">{order.customer?.email}</div></dd>
                <dt>Description</dt><dd>{order.description ?? '—'}</dd>
                <dt>Order status</dt><dd><Badge status={order.status} /></dd>
              </dl>
            </div>
          )}
        </div>
      </div>
    </>
  )
}
