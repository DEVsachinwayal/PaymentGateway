import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { Badge, ErrorNote, PageHead, Spinner, useLoad } from '../components'
import { dt } from '../util'

interface SettingsView { defaultCurrency: string; allowedReturnUrls: string[] }

export default function Settings() {
  const { merchant } = useAuth()
  const { data, error, loading } = useLoad(() => api<SettingsView>('GET', '/api/v1/merchants/me/settings'))
  const [urls, setUrls] = useState('')
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<unknown>(null)

  useEffect(() => { if (data) setUrls(data.allowedReturnUrls.join('\n')) }, [data])

  async function save(e: FormEvent) {
    e.preventDefault()
    setSaved(false); setSaveError(null)
    try {
      await api('PUT', '/api/v1/merchants/me/settings', {
        defaultCurrency: data?.defaultCurrency ?? 'INR',
        allowedReturnUrls: urls.split('\n').map((s) => s.trim()).filter(Boolean),
      })
      setSaved(true)
    } catch (err) { setSaveError(err) }
  }

  if (loading) return <Spinner />
  return (
    <>
      <PageHead title="Settings" sub="Your business and where customers may be sent back to." />
      <ErrorNote error={error} />
      <div className="grid g2">
        <form className="card" onSubmit={save}>
          <h2>Allowed return URLs</h2>
          <p className="muted">After paying, customers can only be sent back to these https addresses. One per line.</p>
          <ErrorNote error={saveError} />
          {saved && <div className="alert ok">Saved.</div>}
          <div className="field"><textarea rows={5} value={urls} onChange={(e) => setUrls(e.target.value)} placeholder="https://shop.example.com/thanks" /></div>
          <button className="btn primary">Save</button>
        </form>
        <div className="card">
          <h2>Business</h2>
          <dl className="kv">
            <dt>Name</dt><dd>{merchant?.name}</dd>
            <dt>Support email</dt><dd>{merchant?.supportEmail}</dd>
            <dt>Currency</dt><dd>{data?.defaultCurrency}</dd>
            <dt>Status</dt><dd>{merchant && <Badge status={merchant.status} />}</dd>
            <dt>Since</dt><dd>{dt(merchant?.createdAt)}</dd>
            <dt>Merchant id</dt><dd className="mono">{merchant?.id}</dd>
          </dl>
        </div>
      </div>
    </>
  )
}
