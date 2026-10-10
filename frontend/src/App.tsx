import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import { Spinner } from './components'
import { Badge } from './components'
import AuthPage from './pages/AuthPage'
import Onboarding from './pages/Onboarding'
import Overview from './pages/Overview'
import Payments from './pages/Payments'
import PaymentDetail from './pages/PaymentDetail'
import Orders from './pages/Orders'
import ApiKeys from './pages/ApiKeys'
import Webhooks from './pages/Webhooks'
import Settings from './pages/Settings'
import Refunds from './pages/Refunds'
import Ledger from './pages/Ledger'
import Settlements from './pages/Settlements'
import Deliveries from './pages/Deliveries'
import Activity from './pages/Activity'
import NewPayment from './pages/NewPayment'
import PaymentLinks from './pages/PaymentLinks'

const NAV: [string, string, string][] = [
  ['/', '🏠', 'Overview'],
  ['/new-payment', '➕', 'Collect payment'],
  ['/payment-links', '🔗', 'Payment links'],
  ['/payments', '💳', 'Payments'],
  ['/orders', '🧾', 'Orders'],
  ['/refunds', '↩️', 'Refunds'],
  ['/settlements', '🏦', 'Settlements'],
  ['/ledger', '📒', 'Ledger'],
]
const DEV: [string, string, string][] = [
  ['/api-keys', '🔑', 'API keys'],
  ['/webhooks', '🔔', 'Webhooks'],
  ['/deliveries', '📬', 'Webhook deliveries'],
  ['/activity', '📜', 'Activity log'],
  ['/settings', '⚙️', 'Settings'],
]
const SOON: string[] = []

export default function App() {
  const { ready, email, merchant, needsOnboarding, logout } = useAuth()
  if (!ready) return <Spinner />
  if (!email) return <AuthPage />
  if (needsOnboarding) return <Onboarding />
  if (!merchant) return <Spinner />

  const link = ([to, icon, label]: [string, string, string]) => (
    <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
      <span>{icon}</span>{label}
    </NavLink>
  )
  return (
    <div className="shell">
      <aside className="side">
        <div className="logo"><i>⚡</i>APG</div>
        <nav className="nav">
          <div className="label">Money</div>
          {NAV.map(link)}
          {SOON.map((s) => <div key={s} className="soon"><span>⏳</span>{s}<em>soon</em></div>)}
          <div className="label">Developers</div>
          {DEV.map(link)}
        </nav>
        <div className="foot">
          <b>{merchant.name}</b>
          <span style={{ opacity: .7 }}>{email}</span>
          <div className="row" style={{ marginTop: 10, justifyContent: 'space-between' }}>
            <Badge status={merchant.status} />
            <button className="btn sm" onClick={logout}>Log out</button>
          </div>
        </div>
      </aside>
      <main className="main">
        {merchant.status !== 'ACTIVE' && (
          <div className="alert warn">
            <b>Test mode.</b> This account is in sandbox. Only TEST API keys work, and no real money moves until your business is activated.
          </div>
        )}
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/new-payment" element={<NewPayment />} />
          <Route path="/payment-links" element={<PaymentLinks />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/payments/:id" element={<PaymentDetail />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/api-keys" element={<ApiKeys />} />
          <Route path="/webhooks" element={<Webhooks />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/refunds" element={<Refunds />} />
          <Route path="/settlements" element={<Settlements />} />
          <Route path="/ledger" element={<Ledger />} />
          <Route path="/deliveries" element={<Deliveries />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
