import { useState } from 'react'
import { api, type MerchantSettlement, type Page } from '../api'
import { Badge, Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { money } from '../util'

const SIZE = 15

export default function Settlements() {
  const [page, setPage] = useState(0)
  const { data, error, loading, reload } = useLoad(() => api<Page<MerchantSettlement>>('GET', `/api/v1/settlements?page=${page}&size=${SIZE}`), [page])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / SIZE)) : 1

  return (
    <>
      <PageHead title="Settlements" sub="Payouts for money the payment provider has settled and we have reconciled.">
        <button className="btn" onClick={reload}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.content.length ? <Empty icon="🏦" text="No settlements yet. A payout appears after the provider's settlement report has been matched with your payments." /> : (
          <table>
            <thead><tr><th>Date</th><th>Provider</th><th className="right">Payments</th><th className="right">Refunds</th><th className="right">Provider fees</th><th className="right">Net</th><th>Status</th></tr></thead>
            <tbody>
              {data.content.map((s) => (
                <tr key={s.id}>
                  <td>{new Date(s.settlementDate).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}</td>
                  <td style={{ textTransform: 'uppercase' }}>{s.provider}</td>
                  <td className="right">{money(s.gross, s.currency)}</td>
                  <td className="right">{s.refunds ? '−' + money(s.refunds, s.currency) : '—'}</td>
                  <td className="right">{s.providerFees ? '−' + money(s.providerFees, s.currency) : '—'}</td>
                  <td className="right"><b>{money(s.net, s.currency)}</b></td>
                  <td>{s.status === 'PAID' ? <><Badge status="ACTIVE" /> <span className="muted" style={{ fontSize: 12 }}>Paid · ref {s.payoutReference}</span></> : <span className="badge b-info">Calculated</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{data?.totalElements ?? 0} settlements · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
      <p className="muted">"Paid" is shown only when a bank payout reference has been recorded as evidence.</p>
    </>
  )
}
