export const money = (minor: number, cur = 'INR') =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: cur }).format(minor / 100)

export const dt = (s?: string | null) =>
  s ? new Date(s).toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'

export const short = (id: string) => id.slice(0, 8)

export function tone(status: string): 'ok' | 'bad' | 'warn' | 'info' | 'mute' {
  switch (status) {
    case 'SUCCESS': case 'PAID': case 'ACTIVE': return 'ok'
    case 'FAILED': case 'CANCELLED': case 'EXPIRED': case 'REVOKED': case 'DEAD': return 'bad'
    case 'PENDING': case 'PROCESSING': case 'VERIFY_REQUIRED': case 'UNKNOWN': case 'SANDBOX': case 'ROTATING': return 'warn'
    case 'CREATED': return 'info'
    default: return 'mute'
  }
}
