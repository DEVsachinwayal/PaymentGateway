import { useState } from 'react'
import { api, type LedgerBalance, type LedgerTx, type Page } from '../api'
import { Empty, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt, money, short } from '../util'

const SIZE = 15
const LABEL: Record<string, string> = { CAPTURE: 'Payment received', REFUND: 'Refund', REVERSAL: 'Correction' }

export default function Ledger() {
  const [page, setPage] = useState(0)
  const bal = useLoad(() => api<LedgerBalance[]>('GET', '/api/v1/ledger/balance'))
  const tx = useLoad(() => api<Page<LedgerTx>>('GET', `/api/v1/ledger/transactions?page=${page}&size=${SIZE}`), [page])
  const pages = tx.data ? Math.max(1, Math.ceil(tx.data.totalElements / SIZE)) : 1
  const b = bal.data?.[0]

  return (
    <>
      <PageHead title="Ledger" sub="What APG owes you, and how it got there.">
        <button className="btn" onClick={() => { bal.reload(); tx.reload() }}>↻ Refresh</button>
      </PageHead>
      <ErrorNote error={bal.error ?? tx.error} />
      {bal.loading ? <Spinner /> : (
        <div className="grid g4" style={{ marginBottom: 16 }}>
          <div className="card stat"><div className="ic">🏦</div><div className="k">Payable to you</div><div className="v">{money(b?.payable ?? 0, b?.currency)}</div></div>
          <div className="card stat"><div className="ic">⬇️</div><div className="k">Payments received</div><div className="v">{money(b?.captured ?? 0, b?.currency)}</div></div>
          <div className="card stat"><div className="ic">🧾</div><div className="k">Fees</div><div className="v">{money(b?.fees ?? 0, b?.currency)}</div></div>
          <div className="card stat"><div className="ic">↩️</div><div className="k">Refunded</div><div className="v">{money(b?.refunded ?? 0, b?.currency)}</div></div>
        </div>
      )}
      {b && b.adjustments !== 0 && <div className="alert warn">Adjustments of {money(b.adjustments, b.currency)} were made by corrections to earlier entries.</div>}
      <div className="card flush">
        <div className="card-h"><h2>Statement</h2></div>
        {tx.loading ? <Spinner /> : !tx.data?.content.length ? <Empty icon="📒" text="No ledger entries yet. They appear after a payment succeeds." /> : (
          <table>
            <thead><tr><th>When</th><th>What</th><th>Reference</th><th className="right">Movement</th></tr></thead>
            <tbody>
              {tx.data.content.map((t) => {
                const mine = t.entries.find((e) => e.account === 'MERCHANT_PAYABLE')
                const sign = mine ? (mine.side === 'CREDIT' ? 1 : -1) : 0
                return (
                  <tr key={t.id}>
                    <td className="muted">{dt(t.occurredAt)}</td>
                    <td><b>{LABEL[t.type] ?? t.type}</b></td>
                    <td className="mono">{short(t.referenceId)}</td>
                    <td className="right"><b style={{ color: sign > 0 ? 'var(--ok)' : sign < 0 ? 'var(--bad)' : undefined }}>
                      {mine ? (sign > 0 ? '+' : '−') + money(mine.amount, t.currency) : '—'}</b></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        <div className="pager">
          <span>{tx.data?.totalElements ?? 0} entries · page {page + 1} of {pages}</span>
          <span className="row">
            <button className="btn sm" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>
            <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next →</button>
          </span>
        </div>
      </div>
    </>
  )
}
