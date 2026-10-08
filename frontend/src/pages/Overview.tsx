import { Link } from 'react-router-dom'
import { api, type Page, type Payment } from '../api'
import { Badge, Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt, money, short } from '../util'

export default function Overview() {
  // Summary is computed from the latest 100 payments until a server-side stats endpoint exists.
  const { data, error, loading } = useLoad(() => api<Page<Payment>>('GET', '/api/v1/payments?page=0&size=100'))
  if (loading) return <Spinner />
  const list = data?.content ?? []
  const ok = list.filter((p) => p.status === 'SUCCESS')
  const done = list.filter((p) => ['SUCCESS', 'FAILED'].includes(p.status))
  const volume = ok.reduce((s, p) => s + p.amount, 0)
  const rate = done.length ? Math.round((ok.length / done.length) * 100) : null
  const open = list.filter((p) => ['CREATED', 'PENDING', 'PROCESSING', 'VERIFY_REQUIRED', 'UNKNOWN'].includes(p.status)).length

  // last 7 days of successful volume
  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - (6 - i))
    return d
  })
  const perDay = days.map((d) => {
    const next = new Date(d); next.setDate(d.getDate() + 1)
    return ok.filter((p) => { const t = new Date(p.createdAt); return t >= d && t < next }).reduce((s, p) => s + p.amount, 0)
  })
  const max = Math.max(...perDay, 1)

  return (
    <>
      <PageHead title="Overview" sub="How your payments are doing." />
      <ErrorNote error={error} />
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <div className="card stat"><div className="ic">💰</div><div className="k">Successful volume</div><div className="v">{money(volume)}</div></div>
        <div className="card stat"><div className="ic">✅</div><div className="k">Successful payments</div><div className="v">{ok.length}</div></div>
        <div className="card stat"><div className="ic">📈</div><div className="k">Success rate</div><div className="v">{rate === null ? '—' : rate + '%'}</div></div>
        <div className="card stat"><div className="ic">⏳</div><div className="k">In progress</div><div className="v">{open}</div></div>
      </div>
      <div className="grid g2">
        <div className="card">
          <h2>Last 7 days</h2>
          <div className="chart">
            {perDay.map((v, i) => (
              <div className="col" key={i} title={money(v)}>
                <div className="bar" style={{ height: `${(v / max) * 100}%` }} />
                <small>{days[i].toLocaleDateString('en-IN', { weekday: 'short' })}</small>
              </div>
            ))}
          </div>
        </div>
        <div className="card flush">
          <div className="card-h"><h2>Recent payments</h2><Link to="/payments">View all</Link></div>
          {list.length === 0 ? <Empty icon="💳" text="No payments yet. Create an API key and send your first order." /> : (
            <table><tbody>
              {list.slice(0, 6).map((p) => (
                <tr key={p.id}>
                  <td><Link to={`/payments/${p.id}`} className="mono">{short(p.id)}</Link><div className="muted" style={{ fontSize: 12 }}>{dt(p.createdAt)}</div></td>
                  <td className="right"><b>{money(p.amount, p.currency)}</b><div><Badge status={p.status} /></div></td>
                </tr>
              ))}
            </tbody></table>
          )}
        </div>
      </div>
      {data && data.totalElements > 100 && <p className="muted">Showing figures for the latest 100 of {data.totalElements} payments.</p>}
    </>
  )
}
