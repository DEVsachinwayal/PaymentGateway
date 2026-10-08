import { useState, type FormEvent } from 'react'
import { api, type ApiKey } from '../api'
import { useAuth } from '../auth'
import { Badge, Empty, ErrorNote, Modal, OneTimeSecret, PageHead, Spinner, useLoad } from '../components'
import { dt } from '../util'

export default function ApiKeys() {
  const { merchant } = useAuth()
  const { data, error, loading, reload } = useLoad(() => api<ApiKey[]>('GET', '/api/v1/api-keys'))
  const [creating, setCreating] = useState(false)
  const [label, setLabel] = useState('')
  const [mode, setMode] = useState<'TEST' | 'LIVE'>('TEST')
  const [fresh, setFresh] = useState<ApiKey | null>(null)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setFormError(null)
    try {
      const k = await api<ApiKey>('POST', '/api/v1/api-keys', { label, mode })
      setFresh(k); setCreating(false); setLabel(''); reload()
    } catch (err) { setFormError(err) } finally { setBusy(false) }
  }
  async function rotate(k: ApiKey) {
    if (!confirm(`Rotate "${k.label}"? The old key keeps working for 24 hours.`)) return
    try { setFresh(await api<ApiKey>('POST', `/api/v1/api-keys/${k.id}/rotate`, { graceHours: 24 })); reload() }
    catch (err) { alert(err instanceof Error ? err.message : 'Failed') }
  }
  async function revoke(k: ApiKey) {
    if (!confirm(`Revoke "${k.label}"? Anything using it stops working immediately.`)) return
    try { await api('DELETE', `/api/v1/api-keys/${k.id}`); reload() }
    catch (err) { alert(err instanceof Error ? err.message : 'Failed') }
  }

  return (
    <>
      <PageHead title="API keys" sub="Use these from your server. Never put a secret key in a website or app.">
        <button className="btn primary" onClick={() => { setFormError(null); setCreating(true) }}>+ Create key</button>
      </PageHead>
      <ErrorNote error={error} />
      <div className="card flush">
        {loading ? <Spinner /> : !data?.length ? <Empty icon="🔑" text="No API keys yet. Create a test key to start." /> : (
          <table>
            <thead><tr><th>Label</th><th>Key id</th><th>Mode</th><th>Status</th><th>Last used</th><th /></tr></thead>
            <tbody>
              {data.map((k) => (
                <tr key={k.id}>
                  <td><b>{k.label}</b><div className="muted" style={{ fontSize: 12 }}>Created {dt(k.createdAt)}</div></td>
                  <td className="mono">{k.keyId}</td>
                  <td><span className={`badge ${k.mode === 'LIVE' ? 'b-bad' : 'b-info'}`}>{k.mode}</span></td>
                  <td><Badge status={k.status} />{k.expiresAt && <div className="muted" style={{ fontSize: 12 }}>until {dt(k.expiresAt)}</div>}</td>
                  <td className="muted">{dt(k.lastUsedAt)}</td>
                  <td className="right">
                    {k.status !== 'REVOKED' && <span className="row" style={{ justifyContent: 'flex-end' }}>
                      <button className="btn sm" onClick={() => rotate(k)}>Rotate</button>
                      <button className="btn sm danger" onClick={() => revoke(k)}>Revoke</button>
                    </span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {creating && (
        <Modal title="Create API key" onClose={() => setCreating(false)}>
          <form onSubmit={create}>
            <ErrorNote error={formError} />
            <div className="field"><label htmlFor="kl">Label</label>
              <input id="kl" value={label} onChange={(e) => setLabel(e.target.value)} required maxLength={100} placeholder="e.g. Website backend" /></div>
            <div className="field"><label>Mode</label>
              <div className="pill-toggle">
                <button type="button" className={mode === 'TEST' ? 'on' : ''} onClick={() => setMode('TEST')}>Test</button>
                <button type="button" className={mode === 'LIVE' ? 'on' : ''} onClick={() => setMode('LIVE')} disabled={merchant?.status !== 'ACTIVE'}>Live</button>
              </div>
              {merchant?.status !== 'ACTIVE' && <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>Live keys unlock once your business is activated.</div>}
            </div>
            <button className="btn primary" disabled={busy}>{busy ? 'Creating…' : 'Create key'}</button>
          </form>
        </Modal>
      )}
      {fresh?.secret && (
        <Modal title="Your new API key" onClose={() => setFresh(null)}>
          <OneTimeSecret value={fresh.secret} note="Copy it now. For your security it is shown only once and cannot be recovered." />
          <p className="muted">Send it as <code>Authorization: Bearer &lt;key&gt;</code> from your server.</p>
          <button className="btn primary" onClick={() => setFresh(null)}>I have saved it</button>
        </Modal>
      )}
    </>
  )
}
