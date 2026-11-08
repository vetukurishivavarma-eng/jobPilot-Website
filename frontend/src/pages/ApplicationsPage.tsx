import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type Application, type ApplicationStatus, type AutoSettings, type FormField } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

const STATUS: Record<ApplicationStatus, [string, string]> = {
  NEEDS_INPUT: ['Needs your answers', 'bg-amber-100 text-amber-800'],
  READY: ['Ready to fill', 'bg-signal-soft text-signal'],
  FILLING: ['Filling…', 'bg-blue-100 text-blue-800'],
  SUBMITTED: ['Submitted', 'bg-ink text-paper'],
  FAILED: ['Needs attention', 'bg-red-100 text-danger'],
}
const SOURCE: Record<string, [string, string]> = {
  PROFILE: ['from your profile', 'text-muted'],
  BANK: ['you answered this before', 'text-muted'],
  AI: ['AI draft: check it', 'text-amber-700 font-semibold'],
  USER: ['your answer', 'text-signal'],
  LETTER: ['your cover letter for this job', 'text-signal'],
}

export function StatusBadge({ s }: { s: ApplicationStatus }) {
  return <span className={`rounded px-2 py-0.5 text-xs font-semibold ${STATUS[s][1]}`}>{STATUS[s][0]}</span>
}

export default function ApplicationsPage() {
  const [items, setItems] = useState<Application[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api.listApplications().then(setItems).catch(e => setError(msg(e)))
  useEffect(() => { load() }, [])

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Applications</h1>
        <p className="mt-1 max-w-xl text-sm text-muted">
          The agent reads each form and fills what it can from your profile. Answer the rest once and it remembers for next time.
        </p>
      </div>
      <AutoPanel onRunFinished={load} />
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      {items === null ? <p className="text-muted">Loading…</p> : items.length === 0 ? (
        <div className="card text-center text-sm text-muted">
          No applications yet. Open a job from Greenhouse or Lever in <Link to="/jobs" className="underline">Jobs</Link> and press <b>Apply with agent</b>.
        </div>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-white">
          {items.map(a => (
            <li key={a.id}>
              <Link to={`/applications/${a.id}`} className="flex flex-wrap items-center gap-3 px-5 py-3 hover:bg-paper">
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">{a.jobTitle ?? 'Job removed'}</p>
                  <p className="text-xs text-muted">{a.company} · updated {new Date(a.updatedAt).toLocaleString()}</p>
                </div>
                {a.auto && <span className="rounded bg-paper px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted">auto</span>}
                {a.missing > 0 && <span className="text-xs text-muted">{a.missing} to answer</span>}
                <StatusBadge s={a.status} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function AutoPanel({ onRunFinished }: { onRunFinished: () => void }) {
  const [s, setS] = useState<AutoSettings | null>(null)
  const [form, setForm] = useState({ enabled: false, autoSubmit: false, minScore: 70, dailyLimit: 10 })
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const apply = (x: AutoSettings) => { setS(x); setForm({ enabled: x.enabled, autoSubmit: x.autoSubmit, minScore: x.minScore, dailyLimit: x.dailyLimit }) }
  useEffect(() => { api.getAuto().then(apply).catch(e => setError(msg(e))) }, [])
  useEffect(() => {
    if (!s?.running) return
    const t = setInterval(() => api.getAuto().then(x => { setS(x); if (!x.running) onRunFinished() }).catch(() => {}), 3000)
    return () => clearInterval(t)
  }, [s?.running])

  if (!s) return error ? <p className="text-sm text-danger">{error}</p> : null
  const dirty = form.enabled !== s.enabled || form.autoSubmit !== s.autoSubmit || form.minScore !== s.minScore || form.dailyLimit !== s.dailyLimit

  async function save() {
    setError(null)
    try { apply(await api.saveAuto(form)); setSaved(true) } catch (e) { setError(msg(e)) }
  }
  async function run() {
    setError(null)
    try { setS(await api.runAuto()) } catch (e) { setError(msg(e)) }
  }
  const sum = s.lastSummary

  return (
    <section className="card space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <div className="mr-auto">
          <h2 className="font-display text-lg font-bold">Auto mode</h2>
          <p className="max-w-xl text-xs text-muted">
            Every 15 minutes the agent prepares applications for your best-matching Greenhouse and Lever jobs. {s.submittedToday} of {s.dailyLimit} submitted today.
          </p>
        </div>
        <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
          <input type="checkbox" className="h-4 w-4" checked={form.enabled} onChange={e => { setForm({ ...form, enabled: e.target.checked }); setSaved(false) }} />
          On
        </label>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className="label" htmlFor="auto-min">Only jobs matching at least {form.minScore}</label>
          <input id="auto-min" type="range" min={0} max={100} step={5} className="w-full accent-[var(--color-signal)]" value={form.minScore}
            onChange={e => { setForm({ ...form, minScore: Number(e.target.value) }); setSaved(false) }} />
        </div>
        <div>
          <label className="label" htmlFor="auto-limit">Most applications per day</label>
          <input id="auto-limit" type="number" min={1} max={50} className="field w-28" value={form.dailyLimit}
            onChange={e => { setForm({ ...form, dailyLimit: Number(e.target.value) }); setSaved(false) }} />
        </div>
      </div>

      <label className="flex cursor-pointer items-start gap-2 text-sm">
        <input type="checkbox" className="mt-0.5 h-4 w-4" checked={form.autoSubmit} onChange={e => { setForm({ ...form, autoSubmit: e.target.checked }); setSaved(false) }} />
        <span>
          <b>Submit for me</b> when every answer is in. A Chrome window opens and the agent presses Submit.
          <span className="block text-xs text-muted">
            Off: applications wait here for your review. On a "verify you're human" check, it stops and leaves the window for you.
          </span>
        </span>
      </label>

      <div className="flex flex-wrap items-center gap-3">
        <button className="btn-ghost" onClick={save} disabled={!dirty}>{saved && !dirty ? 'Saved' : 'Save settings'}</button>
        <button className="btn" onClick={run} disabled={s.running || dirty} title={dirty ? 'Save your settings first' : undefined}>
          {s.running ? 'Running…' : 'Run now'}
        </button>
        {error && <span className="text-sm text-danger">{error}</span>}
      </div>

      {sum && (
        <div className="rounded-md bg-paper p-3 text-sm">
          <p className="font-medium">
            Last run {new Date(sum.at).toLocaleString()}: {sum.prepared} prepared, {sum.needYourAnswers} need your answers, {sum.submitted} submitted
            {sum.waitingForYou > 0 && `, ${sum.waitingForYou} waiting for you`}{sum.failed > 0 && `, ${sum.failed} failed`}.
          </p>
          {sum.notes.length > 0 && <ul className="mt-1 list-disc pl-5 text-xs text-muted">{sum.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
        </div>
      )}
    </section>
  )
}

export function ApplicationPage() {
  const { id } = useParams()
  const nav = useNavigate()
  const [a, setA] = useState<Application | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  const [filling, setFilling] = useState(false)
  const [shot, setShot] = useState<string | null>(null)

  useEffect(() => { api.getApplication(id!).then(setA).catch(e => setError(msg(e))) }, [id])
  // while the browser window is open, follow its progress
  useEffect(() => {
    if (a?.status !== 'FILLING') return
    const t = setInterval(() => api.getApplication(id!).then(setA).catch(() => {}), 3000)
    return () => clearInterval(t)
  }, [a?.status, id])

  if (!a) return error ? <p className="text-danger">{error}</p> : <p className="text-muted">Loading…</p>

  async function fill() {
    setFilling(true)
    setError(null)
    try { setA(await api.fillApplication(a!.id)); window.scrollTo({ top: 0, behavior: 'smooth' }) } catch (e) { setError(msg(e)) } finally { setFilling(false) }
  }

  async function showShot() {
    try { setShot(await api.screenshotUrl(a!.id)) } catch (e) { setError(msg(e)) }
  }

  const locked = a.status === 'FILLING' || a.status === 'SUBMITTED'
  const value = (f: FormField) => draft[f.key] ?? f.value ?? ''
  const edit = (f: FormField, v: string) => { setDraft({ ...draft, [f.key]: v }); setSaved(false) }
  const dirty = Object.keys(draft).length > 0
  const todo = a.fields.filter(f => f.required && !f.value)
  const rest = a.fields.filter(f => !(f.required && !f.value))

  async function save() {
    setSaving(true)
    setError(null)
    try { setA(await api.saveAnswers(a!.id, draft)); setDraft({}); setSaved(true); window.scrollTo({ top: 0, behavior: "smooth" }) } catch (e) { setError(msg(e)) } finally { setSaving(false) }
  }

  async function remove() {
    if (!window.confirm('Delete this application draft? Answers you gave stay remembered.')) return
    try { await api.deleteApplication(a!.id); nav('/applications') } catch (e) { setError(msg(e)) }
  }

  return (
    <div className="space-y-6 pb-24">
      <div>
        <Link to="/applications" className="text-sm text-muted hover:text-ink">← Applications</Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="font-display text-3xl font-bold">{a.jobTitle}</h1>
          <StatusBadge s={a.status} />
        </div>
        <p className="text-sm text-muted">
          {a.company}{a.jobUrl && <> · <a className="underline" href={a.jobUrl} target="_blank" rel="noreferrer">View posting ↗</a></>}
        </p>
      </div>

      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}

      {a.status === 'FILLING' && (
        <section className="rounded-xl border border-blue-300 bg-blue-50 p-5" aria-live="polite">
          <h2 className="font-display text-lg font-bold">Your application is open in a Chrome window</h2>
          <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm">
            <li>Check the filled form{a.error ? ' and fill the fields listed below' : ''}.</li>
            <li>If the site shows a "verify you're human" check, complete it. JobPilot never does that for you.</li>
            <li>Press the site's <b>Submit</b> button. This page updates by itself when the site confirms.</li>
          </ol>
          {a.error && <p className="mt-3 rounded-md bg-amber-100 px-3 py-2 text-sm text-amber-900">{a.error}</p>}
        </section>
      )}
      {a.status === 'SUBMITTED' && (
        <section className="rounded-xl border border-signal bg-signal-soft p-5">
          <h2 className="font-display text-lg font-bold text-signal">Applied ✓</h2>
          <p className="text-sm">The site confirmed your application{a.submittedAt && ` on ${new Date(a.submittedAt).toLocaleString()}`}.</p>
        </section>
      )}
      {a.status !== 'FILLING' && a.error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{a.error}</p>}
      {a.hasScreenshot && a.status !== 'FILLING' && (
        <div>
          {shot ? <div className="max-h-[36rem] overflow-auto rounded-lg border border-line bg-white"><img src={shot} alt="The form as the agent filled it" className="w-full" /></div>
            : <button className="btn-ghost" onClick={showShot}>See the form as the agent filled it</button>}
        </div>
      )}

      {todo.length > 0 && (
        <section className="card space-y-4 border-amber-300">
          <div>
            <h2 className="font-display text-lg font-bold">The agent needs {todo.length} answer{todo.length === 1 ? '' : 's'} from you</h2>
            <p className="text-xs text-muted">Your profile doesn't cover these. Answer once, and the same question on any future form is filled for you.</p>
          </div>
          {todo.map(f => <Field key={f.key} f={f} value={value(f)} onChange={v => edit(f, v)} disabled={locked} />)}
        </section>
      )}

      <section className="card space-y-4">
        <h2 className="font-display text-lg font-bold">Everything else on the form</h2>
        {rest.map(f => <Field key={f.key} f={f} value={value(f)} onChange={v => edit(f, v)} disabled={locked} />)}
      </section>

      <div className="fixed inset-x-0 bottom-0 border-t border-line bg-paper/95 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-3">
          <button className="btn-ghost hover:border-danger hover:text-danger" onClick={remove} disabled={locked}>Delete draft</button>
          <span className="mr-auto text-sm text-muted">
            {dirty ? 'Unsaved changes' : saved ? 'Saved' : a.missing > 0 ? `${a.missing} required answer${a.missing === 1 ? '' : 's'} missing` : 'All required answers are in'}
          </span>
          <button className="btn-ghost" onClick={save} disabled={!dirty || saving || locked}>{saving ? 'Saving…' : 'Save answers'}</button>
          {a.status !== 'SUBMITTED' && (
            <button className="btn" onClick={fill}
              disabled={filling || dirty || a.missing > 0 || a.status === 'FILLING'}
              title={dirty ? 'Save your answers first' : a.missing > 0 ? 'Answer the required questions first' : 'Opens the form in Chrome and fills it for you'}>
              {filling ? 'Opening…' : a.status === 'FILLING' ? 'Window open' : 'Fill application form'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function Field({ f, value, onChange, disabled }: { f: FormField; value: string; onChange: (v: string) => void; disabled: boolean }) {
  const id = 'f-' + f.key.replace(/[^a-zA-Z0-9_-]/g, '_')
  const src = f.source && SOURCE[f.source]
  const label = (
    <div className="mb-1 flex flex-wrap items-baseline gap-x-2">
      <label htmlFor={id} className="text-sm font-medium">
        {f.label}{f.required && <span className="text-danger"> *</span>}
      </label>
      {src && <span className={`text-xs ${src[1]}`}>{src[0]}</span>}
    </div>
  )

  if (f.type === 'FILE')
    return <div>{label}<p className="text-sm">{value ? <>📎 {value} <span className="text-xs text-muted">(your default resume)</span></> : <span className="text-danger">No resume. Upload one on the Resumes page.</span>}</p></div>

  if (f.type === 'SELECT' && f.options.length <= 4)
    return (
      <fieldset>
        {label}
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-labelledby={id}>
          {f.options.map(o => (
            <button type="button" key={o} role="radio" aria-checked={value === o} disabled={disabled} onClick={() => onChange(o)}
              className={`rounded-md border px-3 py-1.5 text-sm ${value === o ? 'border-ink bg-ink text-paper' : 'border-line bg-white hover:border-ink'}`}>
              {o}
            </button>
          ))}
        </div>
      </fieldset>
    )

  if (f.type === 'SELECT')
    return (
      <div>{label}
        <select id={id} className="field" value={value} disabled={disabled} onChange={e => onChange(e.target.value)}>
          <option value="">Choose…</option>
          {f.options.map(o => <option key={o}>{o}</option>)}
        </select>
      </div>
    )

  if (f.type === 'MULTISELECT') {
    const picked = value ? value.split(', ') : []
    return (
      <fieldset>{label}
        <div className="flex flex-wrap gap-3">
          {f.options.map(o => (
            <label key={o} className="flex items-center gap-1.5 text-sm">
              <input type="checkbox" disabled={disabled} checked={picked.includes(o)}
                onChange={e => onChange((e.target.checked ? [...picked, o] : picked.filter(x => x !== o)).join(', '))} />
              {o}
            </label>
          ))}
        </div>
      </fieldset>
    )
  }

  if (f.type === 'TEXTAREA')
    return <div>{label}<textarea id={id} className="field min-h-32" value={value} disabled={disabled} maxLength={10000} onChange={e => onChange(e.target.value)} /></div>

  return <div>{label}<input id={id} className="field" value={value} disabled={disabled} maxLength={10000} onChange={e => onChange(e.target.value)} /></div>
}
