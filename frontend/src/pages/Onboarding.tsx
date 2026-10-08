import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorNote } from '../components'

export default function Onboarding() {
  const { reloadMerchant, email, logout } = useAuth()
  const [name, setName] = useState('')
  const [legalName, setLegalName] = useState('')
  const [supportEmail, setSupportEmail] = useState(email ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      await api('POST', '/api/v1/merchants', { name, supportEmail, ...(legalName ? { legalName } : {}), defaultCurrency: 'INR' })
      // the new role is only in a fresh token: re-login is not needed, the gateway looks the merchant up per request
      await reloadMerchant()
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  return (
    <div className="auth" style={{ gridTemplateColumns: '1fr' }}>
      <section className="form">
        <form className="box card" onSubmit={submit} style={{ padding: 32 }}>
          <h1>Set up your business</h1>
          <p>You will start in test mode. Going live needs verification, which happens outside this dashboard.</p>
          <ErrorNote error={error} />
          <div className="field"><label htmlFor="b">Business name</label>
            <input id="b" value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} /></div>
          <div className="field"><label htmlFor="l">Legal name <span className="muted">(optional)</span></label>
            <input id="l" value={legalName} onChange={(e) => setLegalName(e.target.value)} maxLength={250} /></div>
          <div className="field"><label htmlFor="s">Support email</label>
            <input id="s" type="email" value={supportEmail} onChange={(e) => setSupportEmail(e.target.value)} required /></div>
          <button className="btn primary" style={{ width: '100%', justifyContent: 'center' }} disabled={busy}>{busy ? 'Creating…' : 'Create business'}</button>
          <p style={{ textAlign: 'center', marginTop: 14 }}><a href="#" onClick={(e) => { e.preventDefault(); logout() }}>Log out</a></p>
        </form>
      </section>
    </div>
  )
}
