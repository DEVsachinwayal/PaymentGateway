import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth'
import { ErrorNote } from '../components'

export default function AuthPage() {
  const { login, register } = useAuth()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      if (mode === 'login') await login(email, password)
      else await register(email, password, fullName)
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  return (
    <div className="auth">
      <section className="hero">
        <div className="logo" style={{ padding: 0 }}><i>⚡</i>APG</div>
        <h1>Accept payments.<br />Know where every rupee is.</h1>
        <p>One API for UPI, cards, net banking and wallets — with a verified status, a full timeline and signed webhooks for every payment.</p>
        <ul>
          <li>Server-verified payment status, never trust the browser</li>
          <li>Idempotent APIs, safe to retry</li>
          <li>Secrets shown once, stored only as hashes</li>
        </ul>
      </section>
      <section className="form">
        <form className="box" onSubmit={submit}>
          <h1>{mode === 'login' ? 'Welcome back' : 'Create your account'}</h1>
          <p>{mode === 'login' ? 'Log in to your merchant dashboard.' : 'Start in test mode in under a minute.'}</p>
          <ErrorNote error={error} />
          {mode === 'register' && (
            <div className="field"><label htmlFor="n">Full name</label>
              <input id="n" value={fullName} onChange={(e) => setFullName(e.target.value)} required maxLength={120} autoComplete="name" /></div>
          )}
          <div className="field"><label htmlFor="e">Email</label>
            <input id="e" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" /></div>
          <div className="field"><label htmlFor="p">Password</label>
            <input id="p" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
              minLength={mode === 'register' ? 10 : undefined} maxLength={72} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
            {mode === 'register' && <small className="muted">At least 10 characters.</small>}</div>
          <button className="btn primary" style={{ width: '100%', justifyContent: 'center' }} disabled={busy}>
            {busy ? 'Please wait…' : mode === 'login' ? 'Log in' : 'Create account'}
          </button>
          <p style={{ textAlign: 'center', marginTop: 18 }}>
            {mode === 'login' ? 'New to APG? ' : 'Already have an account? '}
            <a href="#" onClick={(e) => { e.preventDefault(); setError(null); setMode(mode === 'login' ? 'register' : 'login') }}>
              {mode === 'login' ? 'Create an account' : 'Log in'}
            </a>
          </p>
        </form>
      </section>
    </div>
  )
}
