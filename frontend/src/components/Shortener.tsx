import { useState, type FormEvent } from 'react'
import { api, type LinkItem } from '../api'
import { usePoll, ago } from '../hooks'

function StatsPanel({ code }: { code: string }) {
  const { data, error } = usePoll(() => api.stats(code), 3000, code)
  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p className="muted">Loading statistics…</p>
  const max = Math.max(1, ...data.last7Days.map(d => d.clicks))
  return (
    <div className="stats">
      <p><strong>{data.totalClicks}</strong> clicks · <strong>{data.uniqueVisitors}</strong> unique visitors</p>
      <div className="bars" role="img" aria-label={`Clicks per day, last 7 days: ${data.last7Days.map(d => d.clicks).join(', ')}`}>
        {data.last7Days.map(d => (
          <div key={d.date} className="bar-col" title={`${d.date}: ${d.clicks}`}>
            <div className="bar" style={{ height: `${(d.clicks / max) * 100}%` }} />
            <span>{d.date.slice(5)}</span>
          </div>
        ))}
      </div>
      {data.topReferrers.length > 0 && (
        <ul className="plain">
          {data.topReferrers.map(r => <li key={r.referrer}>{r.referrer}: {r.clicks}</li>)}
        </ul>
      )}
      <p className="muted small">{data.consistency}</p>
    </div>
  )
}

export default function Shortener() {
  const links = usePoll(api.links, 3000, 'links')
  const [url, setUrl] = useState('')
  const [alias, setAlias] = useState('')
  const [expires, setExpires] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<LinkItem | null>(null)
  const [open, setOpen] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const item = await api.createLink({
        url: url.trim(),
        alias: alias.trim() || undefined,
        expiresAt: expires ? new Date(expires).toISOString() : undefined,
      })
      setCreated(item)
      setUrl('')
      setAlias('')
      setExpires('')
      links.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the link')
    } finally {
      setBusy(false)
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard blocked */ }
  }

  return (
    <div className="stack">
      <section className="card">
        <h1>Shorten a link</h1>
        <p className="muted">Redirects are served by the API. Clicks flow through Kafka into PostgreSQL, so counts appear a moment later.</p>
        <form onSubmit={submit} className="form">
          <label>Long URL
            <input type="url" required value={url} onChange={e => setUrl(e.target.value)}
                   placeholder="https://example.com/a/very/long/path" autoComplete="off" />
          </label>
          <div className="row">
            <label>Custom alias (optional)
              <input value={alias} onChange={e => setAlias(e.target.value)} placeholder="my-link" pattern="[A-Za-z0-9_\-]{3,32}"
                     title="3 to 32 letters, digits, dash or underscore" />
            </label>
            <label>Expires (optional)
              <input type="datetime-local" value={expires} onChange={e => setExpires(e.target.value)} />
            </label>
          </div>
          <button className="primary" disabled={busy || !url.trim()}>{busy ? 'Creating…' : 'Create short link'}</button>
        </form>
        {error && <p className="error" role="alert">{error}</p>}
        {created && (
          <div className="result" role="status">
            <a href={created.shortUrl} target="_blank" rel="noreferrer">{created.shortUrl}</a>
            <button onClick={() => copy(created.shortUrl)}>{copied ? 'Copied' : 'Copy'}</button>
          </div>
        )}
      </section>

      <section className="card">
        <h2>Recent links</h2>
        {links.error && <p className="error" role="alert">{links.error}</p>}
        {links.loading && <p className="muted">Loading…</p>}
        {links.data && links.data.length === 0 && <p className="muted">No links yet. Create the first one above.</p>}
        <ul className="plain list">
          {links.data?.map(l => (
            <li key={l.code}>
              <div className="link-head">
                <a href={l.shortUrl} target="_blank" rel="noreferrer">{l.shortUrl}</a>
                {l.disabled && <span className="badge bad">disabled</span>}
                {l.expiresAt && <span className="badge">expires {new Date(l.expiresAt).toLocaleString()}</span>}
                <span className="grow" />
                <span className="count">{l.clickCount} clicks</span>
              </div>
              <div className="muted truncate" title={l.targetUrl}>{l.targetUrl} · created {ago(l.createdAt)}</div>
              <div className="actions">
                <button onClick={() => setOpen(open === l.code ? null : l.code)} aria-expanded={open === l.code}>
                  {open === l.code ? 'Hide stats' : 'Stats'}
                </button>
                {!l.disabled && (
                  <button onClick={async () => { await api.disable(l.code); links.reload() }}>Disable</button>
                )}
              </div>
              {open === l.code && <StatsPanel code={l.code} />}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
