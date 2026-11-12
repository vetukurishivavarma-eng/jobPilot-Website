import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type InterviewReport, type InterviewSession, type InterviewSummary, type Job } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')
const TYPE = { BEHAVIOURAL: 'Behavioural', TECHNICAL: 'Technical', ROLE: 'Role', INTRO: 'Opening', FOLLOW_UP: 'Follow-up' } as const
const RUBRIC = [['relevance', 'Relevance'], ['specificity', 'Specificity / STAR'], ['technical', 'Technical'], ['concision', 'Concision']] as const

/** Mock interview coach: start a practice session for a job or role, and the history of past sessions. */
export default function InterviewPage() {
  const nav = useNavigate()
  const [jobs, setJobs] = useState<Job[]>([])
  const [history, setHistory] = useState<InterviewSummary[] | null>(null)
  const [jobId, setJobId] = useState('')
  const [role, setRole] = useState('')
  const [live, setLive] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.listJobs({ status: 'ACTIVE', q: '', includeClosed: false, page: 0, sort: 'match' }).then(p => setJobs(p.items)).catch(e => setError(msg(e)))
    api.listInterviews().then(setHistory).catch(e => setError(msg(e)))
  }, [])

  async function start(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try { nav(`/interview/${(await api.startInterview({ ...(jobId ? { jobId } : { role: role.trim() }), live })).id}`) }
    catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }

  async function remove(id: string) {
    try { await api.deleteInterview(id); setHistory(h => h && h.filter(s => s.id !== id)) } catch (e) { setError(msg(e)) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Interview practice</h1>
        <p className="text-sm text-muted">A live mock interview: it starts with "Tell me about yourself", then follows up on what you actually said, like a real interviewer. 8 questions, then a report on where you stand against what interviewers expect. For practice only, not for use during a real interview.</p>
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
        <button className="btn" disabled={busy || (!jobId && !role.trim())}>{busy ? 'Starting…' : live ? 'Start interview' : 'Start practice'}</button>
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

  if (s.mode === 'LIVE' && !s.report) return <LiveInterview s={s} onChange={setS} />

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

/** Live mode: one question at a time, no feedback until the end (like a real interview), optional camera + voice. */
function LiveInterview({ s, onChange }: { s: InterviewSession; onChange: (s: InterviewSession) => void }) {
  const i = s.items.length - 1
  const item = s.items[i]
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [aways, setAways] = useState(0)

  async function submit() {
    setBusy(true); setError(null)
    try { onChange(await api.answerInterview(s.id, i, draft.trim())); setDraft('') } catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }
  const last = i + 1 >= 8

  return (
    <div className="space-y-5">
      <div>
        <Link to="/interview" className="text-sm text-muted hover:text-ink">← Interview practice</Link>
        <h1 className="mt-2 font-display text-3xl font-bold">{s.title}</h1>
        <p className="text-sm text-muted">Answer each question once, as you would in the room. Feedback comes in the report at the end.</p>
      </div>
      <div className="grid gap-5 md:grid-cols-[1fr_280px]">
        <section className="card space-y-3">
          <div className="flex items-center gap-3">
            <p className="label mr-auto">{TYPE[item.type]} · question {i + 1} of 8</p>
            <div className="h-1.5 w-32 overflow-hidden rounded-full bg-line"><div className="h-full bg-signal" style={{ width: `${(i / 8) * 100}%` }} /></div>
          </div>
          <p className="font-display text-xl font-bold leading-snug">{item.question}</p>
          <textarea className="field min-h-48 leading-relaxed" value={draft} maxLength={5000} aria-label="Your answer"
            placeholder="Speak with the mic or type. Be specific: what you did, why, and what happened."
            onChange={e => setDraft(e.target.value)} />
          <div className="flex flex-wrap items-center gap-3">
            <button className="btn" onClick={submit} disabled={busy || !draft.trim()}>
              {busy ? (last ? 'Writing your report…' : 'Interviewer is thinking…') : last ? 'Finish interview' : 'Answer'}
            </button>
            <Mic onText={t => setDraft(d => (d ? d + ' ' : '') + t)} />
          </div>
          {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
        </section>
        <EyeContact onAway={() => setAways(n => n + 1)} count={aways} />
      </div>
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

/** Browser speech-to-text (Chrome/Edge); hidden where unsupported. */
function Mic({ onText }: { onText: (t: string) => void }) {
  const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
  const rec = useRef<any>(null)
  const [on, setOn] = useState(false)
  useEffect(() => () => rec.current?.stop(), [])
  if (!SR) return null
  function toggle() {
    if (on) { rec.current?.stop(); return }
    const r = new SR()
    r.lang = 'en-IN'; r.continuous = true; r.interimResults = false
    r.onresult = (e: any) => { for (let k = e.resultIndex; k < e.results.length; k++) if (e.results[k].isFinal) onText(e.results[k][0].transcript.trim()) }
    r.onend = () => setOn(false)
    rec.current = r; r.start(); setOn(true)
  }
  return <button type="button" className={on ? 'btn' : 'btn-ghost'} onClick={toggle}>{on ? '● Listening… tap to stop' : '🎤 Speak'}</button>
}

const VISION = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14'
const FACE_MODEL = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task'

/**
 * Eye-contact coach: face tracking runs in the browser, video never leaves the device.
 * "Away" = no face, or head turned sideways, for 2+ seconds. Looking down (typing) doesn't count.
 * ponytail: head turn from 3 landmarks, not true gaze; use the iris landmarks if users want real gaze tracking.
 */
function EyeContact({ onAway, count }: { onAway: () => void; count: number }) {
  const video = useRef<HTMLVideoElement>(null)
  const stop = useRef<() => void>(() => {})
  const [state, setState] = useState<'off' | 'loading' | 'on' | 'error'>('off')
  const [away, setAway] = useState(false)
  useEffect(() => () => stop.current(), [])

  async function start() {
    setState('loading')
    let stream: MediaStream | null = null
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { width: 320, height: 240 } })
      const v = video.current!
      v.srcObject = stream; await v.play()
      const { FaceLandmarker, FilesetResolver } = await import(/* @vite-ignore */ `${VISION}/vision_bundle.mjs`)
      const lm = await FaceLandmarker.createFromOptions(await FilesetResolver.forVisionTasks(`${VISION}/wasm`),
        { baseOptions: { modelAssetPath: FACE_MODEL }, runningMode: 'VIDEO', numFaces: 1 })
      let since = 0, warned = false, timer = 0
      const tick = () => {
        const f = lm.detectForVideo(v, performance.now()).faceLandmarks?.[0]
        let off = !f
        if (f) { // nose tip vs the midpoint of the outer eye corners, in eye-widths
          const l = f[33], r = f[263], nose = f[1]
          off = Math.abs((nose.x - (l.x + r.x) / 2) / Math.max(Math.abs(r.x - l.x), 0.01)) > 0.35
        }
        const now = Date.now()
        if (!off) { since = 0; warned = false; setAway(false) }
        else if (!since) since = now
        else if (now - since > 2000 && !warned) { warned = true; setAway(true); onAway() }
        timer = window.setTimeout(tick, 250)
      }
      const s = stream
      stop.current = () => { clearTimeout(timer); s.getTracks().forEach(t => t.stop()); lm.close() }
      tick()
      setState('on')
    } catch {
      stream?.getTracks().forEach(t => t.stop())
      setState('error')
    }
  }

  return (
    <aside className="card space-y-2 self-start">
      <p className="label">Eye contact</p>
      <video ref={video} muted playsInline className={`w-full -scale-x-100 rounded-md bg-ink ${state === 'on' || state === 'loading' ? '' : 'hidden'} ${away ? 'ring-4 ring-amber-500' : ''}`} />
      {away && <p role="alert" className="rounded-md bg-amber-50 px-3 py-2 text-sm font-semibold text-amber-900">Look at the camera. Interviewers notice when you look away.</p>}
      {state === 'off' && <button className="btn-ghost w-full" onClick={start}>Turn on camera</button>}
      {state === 'loading' && <p className="text-xs text-muted">Starting…</p>}
      {state === 'error' && <p className="text-xs text-danger">Camera unavailable (permission denied or no camera).</p>}
      {state === 'on' && <p className="text-xs text-muted">Looked away {count} time{count === 1 ? '' : 's'}. Video stays on this device.</p>}
      {state === 'off' && <p className="text-xs text-muted">Optional. Warns you when you look away, like a real video interview.</p>}
    </aside>
  )
}
