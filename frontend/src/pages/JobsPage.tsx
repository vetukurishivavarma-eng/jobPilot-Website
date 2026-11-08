import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError, type Job, type JobPage, type JobStatus, type ManualJob, type SourceResult } from '../api'

const TABS = [['ACTIVE', 'Inbox'], ['SAVED', 'Saved'], ['DISMISSED', 'Dismissed'], ['ALL', 'All']] as const
const SOURCE: Record<Job['source'], string> = { greenhouse: 'Greenhouse', lever: 'Lever', workday: 'Workday', adzuna: 'Adzuna', web: 'Web', manual: 'Added by you' }
const LEVEL: Record<string, string> = { INTERN: 'Internship', ENTRY: 'Entry level', MID: 'Mid level', SENIOR: 'Senior', LEAD: 'Lead / staff', MANAGER: 'Manager' }
const DEGREE: Record<string, string> = { BACHELOR: "Bachelor's", MASTER: "Master's", PHD: 'PhD' }
const emptyManual: ManualJob = { title: '', company: '', location: '', url: '', description: '' }

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

function ago(iso: string) {
  const d = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  return d <= 0 ? 'today' : d === 1 ? 'yesterday' : d < 30 ? `${d} days ago` : new Date(iso).toLocaleDateString()
}

function salary(j: Job) {
  if (j.salaryMin == null && j.salaryMax == null) return null
  const f = (n: number) => new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n)
  const range = j.salaryMin != null && j.salaryMax != null && j.salaryMin !== j.salaryMax
    ? `${f(j.salaryMin)}–${f(j.salaryMax)}` : f((j.salaryMin ?? j.salaryMax)!)
  return `${j.salaryCurrency ?? ''} ${range} / yr`.trim()
}

export default function JobsPage() {
  const [status, setStatus] = useState<string>('ACTIVE')
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [includeClosed, setIncludeClosed] = useState(false)
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<'recent' | 'match'>('match')
  const [data, setData] = useState<JobPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState<SourceResult[] | null>(null)
  const [adding, setAdding] = useState(false)

  const refresh = () => api.listJobs({ status, q: query, includeClosed, page, sort }).then(setData).catch(e => setError(msg(e)))
  useEffect(() => { refresh() }, [status, query, includeClosed, page, sort])

  async function find() {
    setRunning(true)
    setError(null)
    try { setReport(await api.runDiscovery()); setPage(0); await refresh() } catch (e) { setError(msg(e)) } finally { setRunning(false) }
  }

  async function setJobStatus(j: Job, s: JobStatus) {
    try { await api.setJobStatus(j.id, s); await refresh() } catch (e) { setError(msg(e)) }
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-display text-3xl font-bold">Jobs</h1>
          <p className="mt-1 max-w-xl text-sm text-muted">
            Every opening in your <Link to="/profile" className="underline">preferred locations</Link>, from every company JobPilot reads, scored against your profile.
          </p>
        </div>
        <button className="btn-ghost" onClick={() => setAdding(a => !a)}>{adding ? 'Close' : 'Add a job'}</button>
        <button className="btn" onClick={find} disabled={running}>{running ? 'Searching…' : 'Find jobs now'}</button>
      </div>

      {report && <Report report={report} onClose={() => setReport(null)} />}
      {adding && <AddJob onAdded={() => { setAdding(false); setStatus('ACTIVE'); setPage(0); refresh() }} />}
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}

      <div className="flex flex-wrap items-center gap-3">
        <div className="flex gap-1 rounded-lg border border-line bg-white p-1" role="tablist">
          {TABS.map(([k, label]) => (
            <button key={k} role="tab" aria-selected={status === k}
              className={`rounded-md px-3 py-1 text-sm font-medium ${status === k ? 'bg-ink text-paper' : 'text-muted hover:text-ink'}`}
              onClick={() => { setStatus(k); setPage(0) }}>{label}</button>
          ))}
        </div>
        <form className="flex min-w-48 flex-1 gap-2" onSubmit={e => { e.preventDefault(); setQuery(q); setPage(0) }}>
          <input className="field" placeholder="Search title or company" value={q} onChange={e => setQ(e.target.value)} aria-label="Search jobs" />
        </form>
        <select className="field w-auto" value={sort} aria-label="Sort jobs" onChange={e => { setSort(e.target.value as 'recent' | 'match'); setPage(0) }}>
          <option value="match">Best match</option>
          <option value="recent">Newest</option>
        </select>
        <label className="flex items-center gap-2 text-sm text-muted">
          <input type="checkbox" checked={includeClosed} onChange={e => { setIncludeClosed(e.target.checked); setPage(0) }} />
          Show closed
        </label>
      </div>

      {data === null ? <p className="text-muted">Loading…</p> : data.items.length === 0 ? (
        <div className="card text-center text-sm text-muted">
          {query ? 'No jobs match that search.' : status === 'ACTIVE'
            ? <>No openings in your preferred locations yet. Press <b>Find jobs now</b>, or add more cities to your <Link to="/profile" className="underline">profile</Link>.</>
            : 'No jobs in this list.'}
        </div>
      ) : (
        <>
          <p className="text-xs text-muted">{data.total} job{data.total === 1 ? '' : 's'}</p>
          <ul className="space-y-3">
            {data.items.map(j => <JobCard key={j.id} job={j} onStatus={s => setJobStatus(j, s)} />)}
          </ul>
          {pages > 1 && (
            <div className="flex items-center justify-center gap-3 text-sm">
              <button className="btn-ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="text-muted">Page {page + 1} of {pages}</span>
              <button className="btn-ghost" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </>
      )}
      {data?.items.some(j => j.source === 'adzuna') && (
        <p className="text-right text-xs text-muted">Search results: <a className="underline" href="https://www.adzuna.in" target="_blank" rel="noreferrer">Jobs by Adzuna</a></p>
      )}
    </div>
  )
}

function Report({ report, onClose }: { report: SourceResult[]; onClose: () => void }) {
  const added = report.reduce((n, r) => n + r.linked, 0)
  return (
    <div className="card">
      <div className="flex items-start gap-3">
        <p className="mr-auto font-semibold">
          {report.length === 0 ? 'No sources to search.' : `${added} new job${added === 1 ? '' : 's'} added to your inbox`}
        </p>
        <button className="text-sm text-muted hover:text-ink" onClick={onClose} aria-label="Close report">✕</button>
      </div>
      {report.length > 0 && (
        <ul className="mt-3 divide-y divide-line text-sm">
          {report.map((r, i) => (
            <li key={i} className="flex flex-wrap gap-x-3 py-1.5">
              <span className="font-medium">{r.label}</span>
              <span className="text-xs uppercase tracking-wide text-muted">{r.source === 'adzuna' ? 'Adzuna search' : 'company'}</span>
              <span className={`ml-auto ${r.error ? 'text-danger' : 'text-muted'}`}>
                {r.error ?? `${r.fetched} open · ${r.linked} new for you`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function JobCard({ job: j, onStatus }: { job: Job; onStatus: (s: JobStatus) => void }) {
  const [detail, setDetail] = useState<Job | null>(null)
  const [open, setOpen] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [preparing, setPreparing] = useState(false)
  const nav = useNavigate()
  const agentCan = (j.source === 'greenhouse' || j.source === 'lever') && !j.closedAt

  async function applyWithAgent() {
    setPreparing(true)
    setErr(null)
    try { nav(`/applications/${(await api.createApplication(j.id)).id}`) } catch (e) { setErr(msg(e)); setPreparing(false) }
  }

  async function toggle() {
    setOpen(!open)
    if (!detail) api.getJob(j.id).then(setDetail).catch(e => setErr(msg(e)))
  }

  const meta = [j.location, j.workplace && j.workplace[0] + j.workplace.slice(1).toLowerCase(), j.employmentType, salary(j)].filter(Boolean)
  const reqs = [
    j.minYears != null && (j.minYears === 0 ? 'Freshers welcome' : `${j.minYears}+ years`),
    j.seniority && LEVEL[j.seniority],
    j.educationLevel && `${DEGREE[j.educationLevel]} degree`,
  ].filter(Boolean)

  return (
    <li className={`card ${j.closedAt ? 'opacity-60' : ''}`}>
      <div className="flex flex-wrap items-start gap-3">
        {j.match?.score != null && <ScoreBadge score={j.match.score} />}
        <div className="min-w-0 flex-1">
          <button className="text-left font-display text-lg font-bold leading-snug hover:text-signal" onClick={toggle} aria-expanded={open}>
            {j.title}
          </button>
          <p className="text-sm">
            <span className="font-medium">{j.company}</span>
            {meta.length > 0 && <span className="text-muted"> · {meta.join(' · ')}</span>}
          </p>
          <p className="mt-1 text-xs text-muted">
            {j.closedAt ? <span className="font-semibold text-danger">Closed {ago(j.closedAt)}</span> : <>Posted {ago(j.postedAt ?? j.discoveredAt)}</>}
            {' · '}{SOURCE[j.source]}
            {j.url && <> · <a className="underline hover:text-ink" href={j.url} target="_blank" rel="noreferrer">View original ↗</a></>}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {agentCan && j.status !== 'DISMISSED' && (
            <button className="btn px-3 py-1.5" onClick={applyWithAgent} disabled={preparing}
              title="The agent reads the application form and fills it from your profile">
              {preparing ? 'Reading form…' : 'Apply with agent'}
            </button>
          )}
          {j.status === 'SAVED'
            ? <button className="btn-ghost border-signal text-signal" onClick={() => onStatus('NEW')}>Saved ✓</button>
            : j.status !== 'DISMISSED' && <button className="btn-ghost" onClick={() => onStatus('SAVED')}>Save</button>}
          {j.status === 'DISMISSED'
            ? <button className="btn-ghost" onClick={() => onStatus('NEW')}>Restore</button>
            : <button className="btn-ghost hover:border-danger hover:text-danger" onClick={() => onStatus('DISMISSED')}>Dismiss</button>}
        </div>
      </div>

      {err && !open && <p className="mt-2 text-sm text-danger">{err}</p>}
      {(reqs.length > 0 || j.skills.length > 0) && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {reqs.map(r => <span key={r as string} className="rounded bg-ink px-2 py-0.5 text-xs font-medium text-paper">{r}</span>)}
          {j.skills.slice(0, open ? undefined : 8).map(s => <span key={s} className="rounded bg-signal-soft px-2 py-0.5 text-xs font-medium text-signal">{s}</span>)}
          {!open && j.skills.length > 8 && <span className="px-1 text-xs text-muted">+{j.skills.length - 8}</span>}
        </div>
      )}

      {open && (
        <div className="mt-4 border-t border-line pt-4">
          {err ? <p className="text-sm text-danger">{err}</p> : !detail ? <p className="text-sm text-muted">Loading…</p> : (
            <>
              {j.match && (j.match.good.length > 0 || j.match.gaps.length > 0) && (
                <div className="mb-3 grid gap-2 text-sm sm:grid-cols-2">
                  <ul className="space-y-1">{j.match.good.map(g => <li key={g}><span className="text-signal">✓</span> {g}</li>)}</ul>
                  <ul className="space-y-1">{j.match.gaps.map(g => <li key={g}><span className="text-amber-700">!</span> {g}</li>)}</ul>
                </div>
              )}
              <p className="mb-2 text-xs text-muted">
                Requirements above are read from the posting automatically and may miss things. Check the original before applying.
                {j.source === 'adzuna' && ' Adzuna only shares a short snippet; open the original for the full description.'}
              </p>
              <div className="max-h-[28rem] overflow-auto whitespace-pre-wrap rounded-md bg-paper p-3 text-sm leading-relaxed">
                {detail.description || 'No description.'}
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Link to={`/jobs/${j.id}/tailor`} className="btn-ghost">Tailor resume & cover letter</Link>
              </div>
              {detail.applyUrl && !j.closedAt && (
                <a className="btn mt-3" href={detail.applyUrl} target="_blank" rel="noreferrer">Open application page ↗</a>
              )}
            </>
          )}
        </div>
      )}
    </li>
  )
}

function AddJob({ onAdded }: { onAdded: () => void }) {
  const [mode, setMode] = useState<'url' | 'manual'>('url')
  const [url, setUrl] = useState('')
  const [m, setM] = useState<ManualJob>(emptyManual)
  const [err, setErr] = useState<ApiError | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setErr(null)
    try {
      await (mode === 'url' ? api.importJob(url.trim()) : api.addManualJob(m))
      onAdded()
    } catch (x) {
      const ae = x instanceof ApiError ? x : new ApiError(0, 'NETWORK', 'Cannot reach the server')
      setErr(ae)
      if (mode === 'url' && ae.status === 422) { setMode('manual'); setM({ ...m, url: url.trim() }) }
    } finally {
      setBusy(false)
    }
  }

  const fe = (k: string) => err?.fields?.[k] && <p className="mt-1 text-xs text-danger">{err.fields[k]}</p>
  const bind = (k: keyof ManualJob) => ({ value: m[k], onChange: (e: { target: { value: string } }) => setM({ ...m, [k]: e.target.value }) })

  return (
    <form className="card space-y-4" onSubmit={submit}>
      <div className="flex gap-4 text-sm">
        {(['url', 'manual'] as const).map(k => (
          <button type="button" key={k} onClick={() => setMode(k)}
            className={`border-b-2 pb-1 font-medium ${mode === k ? 'border-signal text-ink' : 'border-transparent text-muted'}`}>
            {k === 'url' ? 'From a link' : 'Paste it in'}
          </button>
        ))}
      </div>
      {mode === 'url' ? (
        <div>
          <label className="label" htmlFor="job-url">Job posting link</label>
          <input id="job-url" className="field" type="url" required placeholder="https://jobs.lever.co/… or a company careers page"
            value={url} onChange={e => setUrl(e.target.value)} />
          <p className="mt-1 text-xs text-muted">Works with Greenhouse, Lever and most careers pages. LinkedIn and Naukri block this, so paste those in instead.</p>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <div><label className="label" htmlFor="m-title">Job title</label><input id="m-title" className="field" required maxLength={300} {...bind('title')} />{fe('title')}</div>
          <div><label className="label" htmlFor="m-company">Company</label><input id="m-company" className="field" required maxLength={200} {...bind('company')} />{fe('company')}</div>
          <div><label className="label" htmlFor="m-loc">Location</label><input id="m-loc" className="field" maxLength={300} {...bind('location')} />{fe('location')}</div>
          <div><label className="label" htmlFor="m-url">Link (optional)</label><input id="m-url" className="field" type="url" maxLength={1000} {...bind('url')} />{fe('url')}</div>
          <div className="sm:col-span-2">
            <label className="label" htmlFor="m-desc">Job description</label>
            <textarea id="m-desc" className="field min-h-40" required maxLength={50000} {...bind('description')} />{fe('description')}
          </div>
        </div>
      )}
      {err && !err.fields && <p role="alert" className="text-sm text-danger">{err.message}</p>}
      <button className="btn" disabled={busy}>{busy ? 'Adding…' : 'Add job'}</button>
    </form>
  )
}

function ScoreBadge({ score }: { score: number }) {
  const tone = score >= 75 ? 'border-signal text-signal' : score >= 50 ? 'border-amber-500 text-amber-700' : 'border-line text-muted'
  return (
    <div className={`flex h-12 w-12 shrink-0 flex-col items-center justify-center rounded-full border-2 ${tone}`} title="How well this job fits your profile">
      <span className="font-display text-base font-bold leading-none">{score}</span>
      <span className="text-[9px] uppercase tracking-wide">match</span>
    </div>
  )
}
