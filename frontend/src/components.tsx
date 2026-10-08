import { useEffect, useState, type ReactNode } from 'react'
import { tone } from './util'

export const Badge = ({ status }: { status: string }) => (
  <span className={`badge b-${tone(status)}`}>{status.replace('_', ' ')}</span>
)

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-bg" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-label={title}>
        <div className="row" style={{ marginBottom: 16 }}>
          <h2 className="grow" style={{ margin: 0 }}>{title}</h2>
          <button className="btn sm" onClick={onClose} aria-label="Close">✕</button>
        </div>
        {children}
      </div>
    </div>
  )
}

export function Copy({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <button className="btn sm" onClick={() => navigator.clipboard.writeText(text).then(() => { setDone(true); setTimeout(() => setDone(false), 1500) })}>
      {done ? 'Copied ✓' : label}
    </button>
  )
}

/** A secret that the server returns exactly once. Never persisted by the UI. */
export function OneTimeSecret({ value, note }: { value: string; note: string }) {
  return (
    <div>
      <div className="alert warn">{note}</div>
      <div className="secret"><code>{value}</code><Copy text={value} /></div>
    </div>
  )
}

export const Spinner = () => <div className="spin" role="status" aria-label="Loading" />
export const Empty = ({ icon, text }: { icon: string; text: string }) => (
  <div className="empty"><div className="big">{icon}</div><p>{text}</p></div>
)
export const ErrorNote = ({ error }: { error: unknown }) =>
  error ? <div className="alert err">{error instanceof Error ? error.message : 'Something went wrong'}</div> : null

export function PageHead({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) {
  return (
    <div className="topbar">
      <div><h1>{title}</h1>{sub && <p>{sub}</p>}</div>
      <div className="row">{children}</div>
    </div>
  )
}

/** Small loader hook: runs fn on mount/deps change. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let live = true
    setLoading(true)
    fn().then((d) => live && (setData(d), setError(null))).catch((e) => live && setError(e)).finally(() => live && setLoading(false))
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { data, error, loading, reload: () => setTick((t) => t + 1) }
}
