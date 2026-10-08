// Thin client for the APG gateway. Access token lives in memory only; refresh token in sessionStorage.
export interface Envelope<T> {
  success: boolean
  data: T
  error?: { code: string; message: string; details?: unknown[] }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

let accessToken: string | null = null
let refreshing: Promise<boolean> | null = null
const RT = 'apg.rt'

export const session = {
  get hasRefresh() {
    try { return !!sessionStorage.getItem(RT) } catch { return false }
  },
  set(a: string, r: string) {
    accessToken = a
    try { sessionStorage.setItem(RT, r) } catch { /* ignore */ }
  },
  clear() {
    accessToken = null
    try { sessionStorage.removeItem(RT) } catch { /* ignore */ }
  },
  get access() { return accessToken },
}

async function doRefresh(): Promise<boolean> {
  let rt: string | null = null
  try { rt = sessionStorage.getItem(RT) } catch { /* ignore */ }
  if (!rt) return false
  const res = await fetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: rt }),
  })
  if (!res.ok) { session.clear(); return false }
  const body = (await res.json()) as Envelope<{ accessToken: string; refreshToken: string }>
  session.set(body.data.accessToken, body.data.refreshToken)
  return true
}

export async function api<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}, retry = true): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 401 && retry && !path.startsWith('/api/v1/auth/')) {
    refreshing ??= doRefresh().finally(() => { refreshing = null })
    if (await refreshing) return api<T>(method, path, body, headers, false)
    window.dispatchEvent(new Event('apg:logout'))
  }
  if (res.status === 204) return undefined as T
  let json: Envelope<T> | null = null
  try { json = (await res.json()) as Envelope<T> } catch { /* non-JSON */ }
  if (!res.ok || !json?.success) {
    throw new ApiError(res.status, json?.error?.code ?? 'HTTP_' + res.status, json?.error?.message ?? res.statusText)
  }
  return json.data
}

// ---- types ----
export interface Merchant { id: string; name: string; legalName?: string; supportEmail: string; status: string; createdAt: string; defaultCurrency: string }
export interface ApiKey { id: string; keyId: string; label: string; mode: 'TEST' | 'LIVE'; status: string; createdAt: string; expiresAt?: string; lastUsedAt?: string; secret?: string }
export interface Webhook { id: string; url: string; events: string[]; status: string; createdAt: string; signingSecret?: string }
export interface Order { id: string; merchantOrderRef?: string; amount: number; currency: string; status: string; description?: string; customer?: { name?: string; email?: string; phone?: string }; items?: { name: string; quantity: number; unitAmount: number }[]; metadata?: Record<string, string>; createdAt: string }
export interface Payment { id: string; orderId: string; amount: number; currency: string; status: string; paymentMethod?: string; returnUrl?: string; createdAt: string; updatedAt: string }
export interface HistoryItem { from: string | null; to: string; reason?: string; source: string; at: string }
export interface PaymentStatus { paymentId: string; status: string; updatedAt: string; history: HistoryItem[] }
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number }
