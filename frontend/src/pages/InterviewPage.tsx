import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type InterviewSession, type InterviewSummary, type Job } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')
const TYPE = { BEHAVIOURAL: 'Behavioural', TECHNICAL: 'Technical', ROLE: 'Role' } as const
const RUBRIC = [['relevance', 'Relevance'], ['specificity', 'Specificity / STAR'], ['technical', 'Technical'], ['concision', 'Concision']] as const

/** Mock interview coach: start a practice session for a job or role, and the history of past sessions. */
export default function InterviewPage() {
  const nav = useNavigate()
  const [jobs, setJobs] = useState<Job[]>([])
  const [history, setHistory] = useState<InterviewSummary[] | null>(null)
  const [jobId, setJobId] = useState('')
  const [role, setRole] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.listJobs({ status: 'ACTIVE', q: '', includeClosed: false, page: 0, sort: 'match' }).then(p => setJobs(p.items)).catch(e => setError(msg(e)))
    api.listInterviews().then(setHistory).catch(e => setError(msg(e)))
  }, [])

  async function start(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try { nav(`/interview/${(await api.startInterview(jobId ? { jobId } : { role: role.trim() })).id}`) }
    catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }

  async function remove(id: string) {
    try { await api.deleteInterview(id); setHistory(h => h && h.filter(s => s.id !== id)) } catch (e) { setError(msg(e)) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Interview practice</h1>
        <p className="text-sm text-muted">Six practice questions from the job and your profile. Type an answer, get a score and a better version built from your own facts. For practice only, not for use during a real interview.</p>
      </div>
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}

      <form className="card grid gap-3 md:grid-cols-[1fr_auto_1fr_auto] md:items-end" onSubmit={start}>
        <label className="block">
          <span className="label">A job from your list</span>
          <select className="field" value={jobId} onChange={e => { setJobId(e.target.value); if (e.target.value) setRole('') }}>
            <option value="">Choose a job…</option>
            {jobs.map(j => <option key={j.id} value={j.id}>{j.title} · {j.company}</option>)}
          </select>
        </label>
        <span className="pb-2 text-center text-sm text-muted">or</span>
        <label className="block">
          <span className="label">A role</span>
          <input className="field" placeholder="e.g. Backend Engineer" maxLength={120} value={role}
            onChange={e => { setRole(e.target.value); if (e.target.value) setJobId('') }} />
        </label>
        <button className="btn" disabled={busy || (!jobId && !role.trim())}>{busy ? 'Writing questions… (local AI can take a minute)' : 'Start practice'}</button>
      </form>

      <section className="space-y-2">
        <h2 className="font-display text-xl font-bold">Past sessions</h2>
        {history === null ? <p className="text-sm text-muted">Loading…</p> : history.length === 0 ? <p className="text-sm text-muted">None yet.</p> : (
          <ul className="divide-y divide-line rounded-lg border border-line bg-white">
            {history.map(s => (
              <li key={s.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <Link to={`/interview/${s.id}`} className="mr-auto font-medium hover:text-signal">{s.title}</Link>
                <span className="text-sm text-muted">{s.answered}/{s.total} answered</span>
                {s.averageScore != null && <span className="rounded-full bg-signal-soft px-2 py-0.5 text-xs font-semibold text-signal">avg {s.averageScore}/5</span>}
                <span className="text-xs text-muted">{new Date(s.createdAt).toLocaleDateString()}</span>
                <button className="btn-ghost" onClick={() => remove(s.id)}>Delete</button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** One session: a question at a time, the user's answer, and the coach's feedback. */
export function InterviewSessionPage() {
  const { id } = useParams()
  const [s, setS] = useState<InterviewSession | null>(null)
  const [i, setI] = useState(0)
  const [drafts, setDrafts] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.getInterview(id!).then(x => { setS(x); const next = x.items.findIndex(q => !q.answer); setI(next < 0 ? 0 : next) }).catch(e => setError(msg(e)))
  }, [id])

  if (!s) return error ? <p className="text-danger">{error}</p> : <p className="text-muted">Loading…</p>

  const item = s.items[i]
  const draft = drafts[i] ?? item.answer ?? ''
  const fb = item.feedback

  async function submit() {
    setBusy(true); setError(null)
    try { setS(await api.answerInterview(s!.id, i, draft)); setDrafts(d => { const { [i]: _, ...rest } = d; return rest }) }
    catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }

  return (
    <div className="space-y-5">
      <div>
        <Link to="/interview" className="text-sm text-muted hover:text-ink">← Interview practice</Link>
        <h1 className="mt-2 font-display text-3xl font-bold">{s.title}</h1>
        {s.source === 'TEMPLATE' && <p className="text-sm text-muted">Standard questions (the local AI wasn't available to write ones for this job).</p>}
      </div>

      <div className="flex flex-wrap gap-1" role="tablist">
        {s.items.map((q, n) => (
          <button key={n} role="tab" aria-selected={n === i} onClick={() => { setI(n); setError(null) }}
            className={`rounded-md px-3 py-1 text-sm font-medium ${n === i ? 'bg-ink text-paper' : q.answer ? 'bg-signal-soft text-signal' : 'border border-line bg-white text-muted hover:text-ink'}`}>
            {n + 1}{q.feedback?.score != null && ` · ${q.feedback.score}/5`}
          </button>
        ))}
      </div>

      <section className="card space-y-3">
        <p className="label">{TYPE[item.type]} question {i + 1} of {s.items.length}</p>
        <p className="font-display text-lg font-bold">{item.question}</p>
        <textarea className="field min-h-40 leading-relaxed" value={draft} maxLength={5000} aria-label="Your answer"
          placeholder="Answer as you would out loud. For behavioural questions: situation, task, action, result."
          onChange={e => setDrafts({ ...drafts, [i]: e.target.value })} />
        <div className="flex flex-wrap items-center gap-3">
          <button className="btn" onClick={submit} disabled={busy || !draft.trim() || draft === item.answer}>
            {busy ? 'Scoring… (local AI can take a minute)' : item.answer ? 'Score again' : 'Get feedback'}
          </button>
          {i + 1 < s.items.length && <button className="btn-ghost" onClick={() => setI(i + 1)}>Next question →</button>}
        </div>
        {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      </section>

      {fb && (
        <section className="card space-y-4">
          <div className="flex flex-wrap items-baseline gap-3">
            <h2 className="mr-auto font-display text-xl font-bold">Feedback</h2>
            {fb.score != null && <span className="rounded-full bg-signal-soft px-3 py-1 text-sm font-bold text-signal">{fb.score}/5</span>}
          </div>
          <div className="grid gap-2 sm:grid-cols-4">
            {RUBRIC.map(([k, label]) => (
              <div key={k} className="rounded-md border border-line px-3 py-2">
                <p className="text-xs text-muted">{label}</p>
                <p className="font-semibold">{fb.rubric[k] != null ? `${fb.rubric[k]}/5` : '–'}</p>
              </div>
            ))}
          </div>
          {fb.improvements.length > 0 && (
            <div>
              <p className="label">To improve</p>
              <ul className="list-disc pl-5 text-sm">{fb.improvements.map((x, n) => <li key={n}>{x}</li>)}</ul>
            </div>
          )}
          {fb.improvedAnswer && (
            <div>
              <p className="label">A stronger answer, from your own facts</p>
              <p className="whitespace-pre-wrap rounded-md bg-paper px-3 py-2 text-sm leading-relaxed">{fb.improvedAnswer}</p>
            </div>
          )}
          {fb.warnings.length > 0 && (
            <ul className="list-disc rounded-md bg-amber-50 py-2 pl-8 pr-3 text-sm text-amber-900">{fb.warnings.map((w, n) => <li key={n}>{w}</li>)}</ul>
          )}
        </section>
      )}
    </div>
  )
}
