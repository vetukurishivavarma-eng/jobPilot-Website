import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, type Ats, type Company, type SourceResult, type Suggestion } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

export default function CompaniesPage() {
  const [items, setItems] = useState<Company[] | null>(null)
  const [suggestions, setSuggestions] = useState<Suggestion[]>([])
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState<string | null>(null) // what is being followed right now
  const [error, setError] = useState<string | null>(null)
  const [last, setLast] = useState<SourceResult | null>(null)

  const refresh = () => Promise.all([api.listCompanies().then(setItems), api.companySuggestions().then(setSuggestions)]).catch(e => setError(msg(e)))
  useEffect(() => { refresh() }, [])

  async function follow(body: { url: string } | { ats: Ats; token: string }, label: string) {
    setBusy(label)
    setError(null)
    setLast(null)
    try {
      const r = await api.followCompany(body)
      setLast(r.sync)
      setUrl('')
      await refresh()
    } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }

  async function unfollow(c: Company) {
    if (!window.confirm(`Stop following ${c.name}? Jobs already in your list stay there.`)) return
    try { await api.unfollowCompany(c.id); await refresh() } catch (e) { setError(msg(e)) }
  }

  const open = suggestions.filter(s => !s.following)

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Companies</h1>
        <p className="mt-1 max-w-xl text-sm text-muted">
          Follow company career boards on Greenhouse or Lever. New openings matching your desired roles land in your Jobs inbox; we check every few hours.
        </p>
      </div>

      <form className="card space-y-3" onSubmit={(e: FormEvent) => { e.preventDefault(); follow({ url: url.trim() }, url.trim()) }}>
        <label className="label" htmlFor="board-url">Careers page link</label>
        <div className="flex flex-col gap-2 sm:flex-row">
          <input id="board-url" className="field" type="url" required placeholder="https://jobs.lever.co/company or https://boards.greenhouse.io/company"
            value={url} onChange={e => setUrl(e.target.value)} />
          <button className="btn shrink-0" disabled={busy !== null}>{busy ? 'Following…' : 'Follow'}</button>
        </div>
        <p className="text-xs text-muted">Tip: on a company's careers page, click any job. If the address starts with one of the above, paste it here.</p>
        {open.length > 0 && (
          <div>
            <p className="label mt-2">Suggestions</p>
            <div className="flex flex-wrap gap-2">
              {open.map(s => (
                <button type="button" key={s.ats + s.token} className="btn-ghost" disabled={busy !== null}
                  onClick={() => follow({ ats: s.ats, token: s.token }, s.token)}>
                  {busy === s.token ? 'Following…' : `+ ${s.name}`}
                </button>
              ))}
            </div>
          </div>
        )}
      </form>

      {busy && <p className="text-sm text-muted">Reading the job board. Big companies can take up to a minute…</p>}
      {last && (
        <p className={`rounded-md px-3 py-2 text-sm ${last.error ? 'bg-red-50 text-danger' : 'bg-signal-soft text-signal'}`}>
          {last.error ? `Followed ${last.label}, but the first sync failed: ${last.error}`
            : `Following ${last.label}: ${last.fetched} open roles, ${last.linked} match your desired roles and are in your inbox.`}
        </p>
      )}
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}

      {items === null ? <p className="text-muted">Loading…</p> : items.length === 0 ? (
        <p className="text-sm text-muted">You don't follow any companies yet.</p>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-white">
          {items.map(c => (
            <li key={c.id} className="flex flex-wrap items-center gap-3 px-5 py-3">
              <div className="min-w-0 flex-1">
                <a className="font-semibold hover:underline" href={c.boardUrl} target="_blank" rel="noreferrer">{c.name}</a>
                <p className="text-xs text-muted">
                  {c.ats === 'GREENHOUSE' ? 'Greenhouse' : 'Lever'} · {c.lastSyncedAt ? `checked ${new Date(c.lastSyncedAt).toLocaleString()}` : 'not checked yet'}
                </p>
                {c.lastError && <p className="text-xs text-danger">Last check failed: {c.lastError}</p>}
              </div>
              <button className="btn-ghost hover:border-danger hover:text-danger" onClick={() => unfollow(c)}>Unfollow</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
