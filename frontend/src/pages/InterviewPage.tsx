import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, PRO_PERKS, type Billing, type InterviewPack, type UpiOrder, type InterviewReport, type InterviewSession, type InterviewSummary, type Job } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')
const TYPE = { BEHAVIOURAL: 'Behavioural', TECHNICAL: 'Technical', ROLE: 'Role', INTRO: 'Opening', FOLLOW_UP: 'Follow-up', PROBE: 'Check-in' } as const
const RUBRIC = [['relevance', 'Relevance'], ['specificity', 'Specificity / STAR'], ['technical', 'Technical'], ['concision', 'Concision']] as const

/** Mock interview coach: start a practice session for a job or role, and the history of past sessions. */
export default function InterviewPage() {
  const nav = useNavigate()
  const [jobs, setJobs] = useState<Job[]>([])
  const [history, setHistory] = useState<InterviewSummary[] | null>(null)
  const [jobId, setJobId] = useState('')
  const [role, setRole] = useState('')
  const [pack, setPack] = useState('')
  const [packs, setPacks] = useState<InterviewPack[]>([])
  const [live, setLive] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [billing, setBilling] = useState<Billing | null>(null)
  const [needsPlan, setNeedsPlan] = useState(false)

  useEffect(() => {
    api.billing().then(setBilling).catch(() => {})
    api.interviewPacks().then(setPacks).catch(() => {})
    api.listJobs({ status: 'ACTIVE', q: '', includeClosed: false, page: 0, sort: 'match' }).then(p => setJobs(p.items)).catch(e => setError(msg(e)))
    api.listInterviews().then(setHistory).catch(e => setError(msg(e)))
  }, [])

  async function start(e?: React.FormEvent) {
    e?.preventDefault()
    setBusy(true); setError(null)
    try {
      const x = await api.startInterview({ ...(pack ? { pack } : jobId ? { jobId } : { role: role.trim() }), live })
      nav(live ? `/live/${x.id}` : `/interview/${x.id}`)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PAYMENT_REQUIRED') { setNeedsPlan(true); api.billing().then(setBilling).catch(() => {}) }
      else setError(msg(e))
    } finally { setBusy(false) }
  }

  async function remove(id: string) {
    try { await api.deleteInterview(id); setHistory(h => h && h.filter(s => s.id !== id)) } catch (e) { setError(msg(e)) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Interview practice</h1>
        <p className="text-sm text-muted">A spoken mock interview with Arjun, our AI interviewer: he asks out loud, you answer out loud, and he follows up on what you actually said. 8 questions, then a report on where you stand against what interviewers expect, plus a recording you can download for an hour. Camera and microphone on. For practice only, not for use during a real interview.</p>
      </div>
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      {billing?.enabled && (needsPlan || billing.credits === 0)
        ? <Plans billing={billing} onPaid={b => { setBilling(b); if (b.credits > 0 && needsPlan) { setNeedsPlan(false); start() } }} />
        : billing?.enabled && <p className="text-sm text-muted">{billing.credits} live interview{billing.credits === 1 ? '' : 's'} left.</p>}

      {billing?.proUntil && <p className="text-sm font-semibold text-signal">Pro until {new Date(billing.proUntil).toLocaleDateString()}</p>}
      {billing?.admin && <p className="text-sm"><a className="underline" href="/admin/payments">Admin: payments to approve</a></p>}
      {packs.length > 0 && (
        <section className="card space-y-2">
          <div>
            <p className="font-semibold">Company interview packs</p>
            <p className="text-xs text-muted">In the style of each company's fresher rounds, from how they're publicly described. Fresh questions every time, never leaked ones.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            {packs.map(p => (
              <button key={p.id} type="button" onClick={() => { setPack(pack === p.id ? '' : p.id); setJobId(''); setRole('') }}
                className={`rounded-full border px-3 py-1.5 text-sm ${pack === p.id ? 'border-signal bg-signal text-white' : 'border-line bg-white hover:border-ink'}`}
                title={`${p.role}: ${p.rounds}`}>
                {p.company} <span className="text-xs opacity-75">· {p.rounds}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      <form className="card grid gap-3 md:grid-cols-[1fr_auto_1fr_auto] md:items-end" onSubmit={start}>
        <label className="block">
          <span className="label">A job from your list</span>
          <select className="field" value={jobId} onChange={e => { setJobId(e.target.value); if (e.target.value) { setRole(''); setPack('') } }}>
            <option value="">Choose a job…</option>
            {jobs.map(j => <option key={j.id} value={j.id}>{j.title} · {j.company}</option>)}
          </select>
        </label>
        <span className="pb-2 text-center text-sm text-muted">or</span>
        <label className="block">
          <span className="label">A role</span>
          <input className="field" placeholder="e.g. Backend Engineer" maxLength={120} value={role}
            onChange={e => { setRole(e.target.value); if (e.target.value) { setJobId(''); setPack('') } }} />
        </label>
        <button className="btn" disabled={busy || (!jobId && !role.trim() && !pack)}>{busy ? 'Starting…' : pack ? `Start ${packs.find(p => p.id === pack)?.company} interview` : live ? 'Start interview' : 'Start practice'}</button>
        <label className="flex items-center gap-2 text-sm text-muted md:col-span-4">
          <input type="checkbox" checked={!live} onChange={e => setLive(!e.target.checked)} />
          Practice set instead: 6 fixed questions, feedback after each answer
        </label>
      </form>

      <section className="space-y-2">
        <h2 className="font-display text-xl font-bold">Past sessions</h2>
        {history === null ? <p className="text-sm text-muted">Loading…</p> : history.length === 0 ? <p className="text-sm text-muted">None yet.</p> : (
          <ul className="divide-y divide-line rounded-lg border border-line bg-white">
            {history.map(s => (
              <li key={s.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <Link to={`/interview/${s.id}`} className="mr-auto font-medium hover:text-signal">{s.title}</Link>
                <span className="text-sm text-muted">{s.mode === 'LIVE' ? 'Interview' : 'Practice'} · {s.answered}/{s.total} answered</span>
                {s.readiness != null && <span className="rounded-full bg-ink px-2 py-0.5 text-xs font-semibold text-paper">ready {s.readiness}%</span>}
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

  if (s.mode === 'LIVE' && !s.report && !s.ended && !s.done) return <Navigate to={`/live/${s.id}`} replace />

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
      {s.recordingExpiresAt && <Recording s={s} />}
      {s.mode === 'LIVE' && s.done && !s.report && <RetryReport s={s} onChange={setS} />}
      {s.report && <Report r={s.report} />}
      {s.report && <h2 className="font-display text-xl font-bold">Question by question</h2>}

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
        <textarea className="field min-h-40 leading-relaxed" value={draft} maxLength={5000} aria-label="Your answer" readOnly={s.mode === 'LIVE'}
          placeholder="Answer as you would out loud. For behavioural questions: situation, task, action, result."
          onChange={e => setDrafts({ ...drafts, [i]: e.target.value })} />
        <div className="flex flex-wrap items-center gap-3">
          {s.mode !== 'LIVE' && <button className="btn" onClick={submit} disabled={busy || !draft.trim() || draft === item.answer}>
            {busy ? 'Scoring… (local AI can take a minute)' : item.answer ? 'Score again' : 'Get feedback'}
          </button>}
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

function Report({ r }: { r: InterviewReport }) {
  return (
    <section className="card space-y-4">
      <div className="flex flex-wrap items-center gap-4">
        <div className="grid h-20 w-20 place-items-center rounded-full bg-ink text-paper">
          <span className="font-display text-2xl font-bold">{r.readiness ?? '–'}<span className="text-sm">%</span></span>
        </div>
        <div className="flex-1">
          <h2 className="font-display text-xl font-bold">Interview readiness</h2>
          {r.verdict ? <p className="text-sm">{r.verdict}</p> : <p className="text-sm text-muted">From your scores (the AI wasn't available for a written review).</p>}
        </div>
      </div>
      {r.gaps.length > 0 && (
        <div>
          <p className="label">Where you're behind the market standard</p>
          <ul className="space-y-2">{r.gaps.map((g, n) => (
            <li key={n} className="rounded-md border-l-4 border-amber-500 bg-amber-50 px-3 py-2 text-sm"><b>{g.area}</b>{g.detail && <span> — {g.detail}</span>}</li>
          ))}</ul>
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        {r.strengths.length > 0 && <div><p className="label">Strengths</p><ul className="list-disc pl-5 text-sm">{r.strengths.map((x, n) => <li key={n}>{x}</li>)}</ul></div>}
        {r.focus.length > 0 && <div><p className="label">Practise next</p><ul className="list-disc pl-5 text-sm">{r.focus.map((x, n) => <li key={n}>{x}</li>)}</ul></div>}
      </div>
    </section>
  )
}

/** The interview recording: downloadable until it's auto-deleted (1 hour after the interview). */
function Recording({ s }: { s: InterviewSession }) {
  const [error, setError] = useState<string | null>(null)
  const until = new Date(s.recordingExpiresAt!).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  async function download() {
    setError(null)
    try {
      const res = await api.recordingFile(s.id)
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const ext = blob.type.includes('mp4') ? 'mp4' : 'webm'
      Object.assign(document.createElement('a'), { href: url, download: `jobpilot-interview-${s.id.slice(0, 8)}.${ext}` }).click()
      URL.revokeObjectURL(url)
    } catch (e) { setError(msg(e)) }
  }
  return (
    <section className="card flex flex-wrap items-center gap-3">
      <div className="mr-auto">
        <p className="font-semibold">Your recording is ready</p>
        <p className="text-sm text-muted">Deleted automatically at {until}. Download it to keep it.</p>
      </div>
      <button className="btn" onClick={download}>Download recording</button>
      {error && <p className="w-full text-sm text-danger">{error}</p>}
    </section>
  )
}

function RetryReport({ s, onChange }: { s: InterviewSession; onChange: (s: InterviewSession) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function retry() {
    setBusy(true); setError(null)
    try { onChange(await api.interviewReport(s.id)) } catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }
  return (
    <section className="card space-y-2">
      <p className="font-semibold">Your answers are saved, but the report couldn't be written.</p>
      <button className="btn" onClick={retry} disabled={busy}>{busy ? 'Writing…' : 'Write my report'}</button>
      {error && <p className="text-sm text-danger">{error}</p>}
    </section>
  )
}

/** Live interviews are paid when billing is on: Rs 10 first one, Rs 49 each, or 4 in 7 days for Rs 200. Razorpay's own page takes the payment. */
function Plans({ billing, onPaid }: { billing: Billing; onPaid: (b: Billing) => void }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [opened, setOpened] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [upi, setUpi] = useState<UpiOrder | null>(null)
  const [utr, setUtr] = useState('')
  const [sent, setSent] = useState(false)
  async function buy(plan: Billing['plans'][number]['id']) {
    setBusy(plan); setError(null); setSent(false); setUtr('')
    try {
      if (billing.mode === 'UPI') setUpi(await api.upiOrder(plan))
      else { window.open((await api.buy(plan)).payUrl, '_blank', 'noopener'); setOpened(true) }
    } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }
  async function claim() {
    setBusy('utr'); setError(null)
    try { onPaid(await api.claimUpi(upi!.purchaseId, utr.trim())); setSent(true) } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }
  async function check() {
    try { const b = await api.billing(); onPaid(b); if (b.credits === 0) setError("We haven't received the payment yet. If you paid, wait a few seconds and check again.") } catch (e) { setError(msg(e)) }
  }
  return (
    <section className="card space-y-3">
      <p className="font-semibold">Get your live interview, or go Pro</p>
      <div className="grid gap-3 sm:grid-cols-2">
        {billing.plans.map(p => (
          <button key={p.id} onClick={() => buy(p.id)} disabled={!!busy}
            className={`rounded-lg border bg-white p-4 text-left hover:border-ink disabled:opacity-60 ${p.id === 'PRO' ? 'border-signal ring-2 ring-signal-soft sm:col-span-2' : 'border-line'}`}>
            <p className="font-display text-2xl font-bold">₹{p.rupees}{p.id === 'PRO' && <span className="ml-2 rounded bg-signal px-2 py-0.5 align-middle text-xs text-white">PRO</span>}</p>
            <p className="text-sm">{p.label}</p>
            {p.id === 'PRO' && <p className="text-xs text-signal">{PRO_PERKS}</p>}
            <p className="text-xs text-muted">{p.interviews} interview{p.interviews === 1 ? '' : 's'}{p.days ? `, use within ${p.days} days` : ''} · report + recording</p>
          </button>
        ))}
      </div>
      {upi && !sent && (
        <div className="flex flex-wrap items-start gap-4 rounded-lg border border-signal bg-signal-soft p-4">
          <img src={upi.qrUrl} alt="UPI QR code" className="size-44 rounded bg-white p-1" />
          <div className="min-w-0 flex-1 space-y-2 text-sm">
            <p className="font-semibold">Pay ₹{upi.rupees} by UPI · {upi.label}</p>
            <p className="text-muted">Scan with GPay, PhonePe, Paytm or any UPI app (on a phone: <a className="underline" href={upi.upiUrl}>open your UPI app</a>). Keep the note <b>JobPilot {upi.ref}</b>.</p>
            <label className="block">
              <span className="label">Then enter the 12-digit UPI reference (UTR)</span>
              <input className="field" inputMode="numeric" maxLength={14} placeholder="e.g. 412345678901" value={utr} onChange={e => setUtr(e.target.value)} />
            </label>
            <button className="btn" disabled={busy === 'utr' || utr.replace(/\s/g, '').length !== 12} onClick={claim}>I've paid</button>
          </div>
        </div>
      )}
      {sent && <p className="text-sm font-semibold text-signal">Thanks! We'll confirm your payment within a few hours, then it unlocks automatically.</p>}
      {(billing.pending ?? 0) > 0 && !sent && <p className="text-sm text-muted">{billing.pending} payment{billing.pending === 1 ? '' : 's'} being checked. It unlocks as soon as it's confirmed.</p>}
      {opened && <button className="btn-ghost" onClick={check}>I've paid, continue</button>}
      {error && <p className="text-sm text-danger">{error}</p>}
    </section>
  )
}
