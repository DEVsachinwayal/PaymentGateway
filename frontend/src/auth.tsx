import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, ApiError, session, type Merchant } from './api'

interface Ctx {
  ready: boolean
  email: string | null
  merchant: Merchant | null
  needsOnboarding: boolean
  login(email: string, password: string): Promise<void>
  register(email: string, password: string, fullName: string): Promise<void>
  logout(): Promise<void>
  reloadMerchant(): Promise<void>
}

const AuthCtx = createContext<Ctx>(null as unknown as Ctx)
export const useAuth = () => useContext(AuthCtx)

interface Tokens { accessToken: string; refreshToken: string }

function emailFromJwt(): string | null {
  try {
    const t = session.access
    if (!t) return null
    const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof p.email === 'string' ? p.email : null
  } catch { return null }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false)
  const [email, setEmail] = useState<string | null>(null)
  const [merchant, setMerchant] = useState<Merchant | null>(null)
  const [needsOnboarding, setNeedsOnboarding] = useState(false)

  const reloadMerchant = useCallback(async () => {
    try {
      setMerchant(await api<Merchant>('GET', '/api/v1/merchants/me'))
      setNeedsOnboarding(false)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) { setMerchant(null); setNeedsOnboarding(true) } else throw e
    }
  }, [])

  const reset = useCallback(() => {
    session.clear(); setEmail(null); setMerchant(null); setNeedsOnboarding(false)
  }, [])

  useEffect(() => {
    const onLogout = () => reset()
    window.addEventListener('apg:logout', onLogout)
    return () => window.removeEventListener('apg:logout', onLogout)
  }, [reset])

  // restore the session after a page reload: the refresh token is the only thing persisted
  useEffect(() => {
    (async () => {
      try {
        if (session.hasRefresh) {
          const rt = sessionStorage.getItem('apg.rt')
          const t = await api<Tokens>('POST', '/api/v1/auth/refresh', { refreshToken: rt })
          session.set(t.accessToken, t.refreshToken)
          setEmail(emailFromJwt())
          await reloadMerchant()
        }
      } catch { reset() } finally { setReady(true) }
    })()
  }, [reloadMerchant, reset])

  const value: Ctx = {
    ready, email, merchant, needsOnboarding, reloadMerchant,
    async login(e, p) {
      const t = await api<Tokens>('POST', '/api/v1/auth/login', { email: e, password: p })
      session.set(t.accessToken, t.refreshToken)
      setEmail(emailFromJwt() ?? e)
      await reloadMerchant()
    },
    async register(e, p, fullName) {
      await api('POST', '/api/v1/auth/register', { email: e, password: p, fullName })
      await value.login(e, p)
    },
    async logout() {
      try {
        const rt = sessionStorage.getItem('apg.rt')
        if (rt) await api('POST', '/api/v1/auth/logout', { refreshToken: rt })
      } catch { /* best effort */ }
      reset()
    },
  }
  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>
}
