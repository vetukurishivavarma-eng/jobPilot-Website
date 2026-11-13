import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type InterviewSession, type Signals } from '../api'
import { Engine, inApp, speak, stopSpeaking, toApp } from '../live'
import { confirmDialog } from '../dialog'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')
const INTERVIEWER = 'Arjun'
const TOTAL = 8
const NUDGE = 'Please look at the camera.' // the server strips this from answer transcripts (the mic hears it)
type Phase = 'loading' | 'ready' | 'asking' | 'listening' | 'typing' | 'thinking' | 'warned' | 'finishing' | 'policy' | 'error'

/**
 * The live mock interview, spoken: a faceless interviewer asks out loud, the candidate answers out loud, the turn ends
 * when they stop talking. Used by the website and, inside a WebView, by the phone app (which provides the voice,
 * notifications, downloads and the screen-recording block).
 */
export default function LivePage() {
  const { id } = useParams()
  const nav = useNavigate()
  const video = useRef<HTMLVideoElement>(null)
  const engine = useRef<Engine | null>(null)
  const cancelListen = useRef<() => void>(() => {})
  const session = useRef<InterviewSession | null>(null)
  const phonePending = useRef(false)
  const misses = useRef(0)
  const lastNudge = useRef(0)
  const left = useRef(false) // the candidate ended it: drop any reply still in flight
  const [s, setS] = useState<InterviewSession | null>(null)
  const [phase, setPhase] = useState<Phase>('loading')
  const [error, setError] = useState<string | null>(null)
  const [away, setAway] = useState(false)
  const [level, setLevel] = useState(0)
  const [draft, setDraft] = useState('')
  const [note, setNote] = useState<string | null>(null)

  const update = (x: InterviewSession) => { session.current = x; setS(x) }

  useEffect(() => {
    api.getInterview(id!).then(x => {
      update(x)
      if (x.mode !== 'LIVE' || x.report || x.ended) return finished(x)
      setPhase('ready')
    }).catch(e => { setError(msg(e)); setPhase('error') })
    return () => { cancelListen.current(); stopSpeaking(); engine.current?.stop() }
  }, [id])

  useEffect(() => { // mic meter
    if (phase !== 'listening') return
    const t = setInterval(() => setLevel(engine.current?.level ?? 0), 120)
    return () => clearInterval(t)
  }, [phase])

  function finished(x: InterviewSession) {
    if (inApp()) toApp({ type: 'finished', id: x.id })
    else nav(`/interview/${x.id}`, { replace: true })
  }

  async function begin() {
    setError(null)
    const e = new Engine()
    e.onAway = a => {
      setAway(a)
      // said out loud while they answer, at most every 20 s; never over a question
      if (a && (phaseRef.current === 'listening' || phaseRef.current === 'typing') && Date.now() - lastNudge.current > 20000) {
        lastNudge.current = Date.now()
        speak(NUDGE)
      }
    }
    e.onPhone = () => { phonePending.current = true; if (phaseRef.current === 'listening' || phaseRef.current === 'typing') handlePhone() }
    try { await e.start(video.current!) } catch {
      setError('JobPilot needs your camera and microphone for the interview. Allow them and try again.')
      return
    }
    engine.current = e
    if (!inApp() && 'Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {})
    const x = session.current!
    const answered = x.items.filter(i => i.answer).length
    await speak(answered ? `Welcome back. Let's continue.` : `Hi, I'm ${INTERVIEWER}. I'll be taking your interview today. Answer the way you would in the real room, and take your time.`)
    ask(x)
  }

  // the phase as of now, for callbacks created earlier
  const phaseRef = useRef<Phase>('loading')
  useEffect(() => { phaseRef.current = phase }, [phase])

  async function ask(x: InterviewSession) {
    const item = x.items[x.items.length - 1]
    setPhase('asking')
    await speak(item.question)
    if (!engine.current || left.current) return
    if (phonePending.current) return handlePhone()
    listen()
  }

  function listen() {
    setPhase('listening')
    engine.current!.beginAnswer(false)
    cancelListen.current = engine.current!.listen(() => submitVoice())
  }

  async function submitVoice() {
    cancelListen.current()
    const { audio, signals } = await engine.current!.endAnswer()
    if (!audio) return typeInstead()
    setPhase('thinking')
    const x = session.current!
    const fd = new FormData()
    fd.append('audio', audio, 'answer.webm')
    fd.append('signals', JSON.stringify(signals))
    try {
      misses.current = 0
      after(await api.answerInterviewAudio(x.id, x.items.length - 1, fd))
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'NO_SPEECH' || e.code === 'STT_UNAVAILABLE') && ++misses.current <= 2) {
        await speak(e.code === 'NO_SPEECH' ? "Sorry, I didn't catch that. Could you say it again?" : 'Sorry, I lost you for a moment. Please answer again.')
        return listen()
      }
      setError(msg(e))
      typeInstead()
    }
  }

  function typeInstead() {
    cancelListen.current()
    engine.current?.endAnswer()
    engine.current?.beginAnswer(true)
    setPhase('typing')
  }

  async function submitTyped() {
    const text = draft.trim()
    if (!text) return
    const { signals } = await engine.current!.endAnswer()
    setPhase('thinking')
    const x = session.current!
    try { setDraft(''); after(await api.answerInterview(x.id, x.items.length - 1, text, signals as Signals)) }
    catch (e) { setError(msg(e)); engine.current!.beginAnswer(true); setPhase('typing') }
  }

  function after(x: InterviewSession) {
    if (left.current) return
    update(x)
    setError(null)
    if (x.ended === 'QUIT') return quit(x)
    if (x.ended) return policy()
    if (x.done) return finish(x)
    ask(x)
  }

  async function handlePhone() {
    phonePending.current = false
    cancelListen.current()
    stopSpeaking()
    await engine.current?.endAnswer() // the interrupted answer is discarded; the question is asked again
    try {
      const x = await api.interviewViolation(session.current!.id, 'PHONE')
      update(x)
      if (x.ended) return policy()
      setPhase('warned')
      await speak('I can see a phone on camera. Please put it away. If I see it again, the interview will end.')
    } catch (e) { setError(msg(e)) }
  }

  async function leave() {
    if (!await confirmDialog('End this interview?', { message: "You won't get a score for it.", ok: 'End interview', danger: true })) return
    try { quit(await api.endInterview(session.current!.id)) } catch (e) { setError(msg(e)) }
  }

  /** Ended by the End button or by saying "stop the interview": no more questions, no recording kept. */
  async function quit(x: InterviewSession) {
    left.current = true
    cancelListen.current()
    stopSpeaking()
    await engine.current?.stop()
    engine.current = null
    update(x)
    finished(x)
  }

  async function policy() {
    cancelListen.current()
    setPhase('policy')
    await engine.current?.stop()
    engine.current = null
    await speak('A phone was seen on camera again. This violates JobPilot Policy, so this interview has been stopped.')
  }

  async function finish(x: InterviewSession) {
    setPhase('finishing')
    await speak("That's the end of the interview. Thank you! I'm preparing your report now.")
    const recording = await engine.current?.stop()
    engine.current = null
    let latest = x
    if (!latest.report) try { latest = await api.interviewReport(x.id) } catch { /* the report page offers a retry */ }
    if (recording) {
      setNote('Saving your recording…')
      try {
        latest = await api.uploadRecording(x.id, recording)
        const body = 'Your interview recording is ready to download. It is deleted automatically after 1 hour.'
        if (inApp()) toApp({ type: 'notify', title: 'Recording ready', body })
        else if ('Notification' in window && Notification.permission === 'granted') new Notification('JobPilot: recording ready', { body })
      } catch (e) { setNote(`The recording couldn't be saved: ${msg(e)}`) }
    }
    update(latest)
    finished(latest)
  }

  const item = s?.items[s.items.length - 1]
  const n = s ? s.items.filter(i => i.answer && i.type !== 'PROBE').length + (phase === 'thinking' ? 0 : 1) : 0
  const speaking = phase === 'asking' || phase === 'warned' || phase === 'finishing' || phase === 'policy'

  return (
    <div className="flex min-h-screen flex-col bg-[#0f0d17] text-white">
      <header className="flex items-center gap-3 px-4 py-3 text-sm text-white/60">
        <span className="font-semibold text-white">JobPilot</span>
        <span className="mr-auto truncate">{s?.title}</span>
        {s && phase !== 'ready' && phase !== 'loading' && <span>Question {Math.min(n, TOTAL)} of {TOTAL}</span>}
        <span className="flex items-center gap-1.5"><span className="h-2 w-2 animate-pulse rounded-full bg-red-500" />REC</span>
        {s && !['loading', 'finishing', 'policy', 'error'].includes(phase) && (
          <button className="rounded-lg bg-red-500/20 px-3 py-1.5 font-semibold text-red-200 hover:bg-red-500/30" onClick={leave}>End interview
          </button>
        )}
      </header>

      <main className="relative flex flex-1 flex-col items-center justify-center gap-6 px-4 pb-6">
        {/* the faceless interviewer */}
        <div className="relative grid place-items-center">
          {speaking && <span className="absolute h-44 w-44 animate-ping rounded-full bg-violet-500/20" />}
          <div className={`grid h-36 w-36 place-items-center rounded-full bg-gradient-to-br from-violet-600 to-fuchsia-500 text-5xl font-bold shadow-2xl transition ${speaking ? 'scale-105' : ''}`}>
            {INTERVIEWER[0]}
          </div>
        </div>
        <p className="text-sm text-white/60">{INTERVIEWER} · AI interviewer {speaking ? '· speaking' : phase === 'thinking' ? '· thinking…' : ''}</p>

        {item && phase !== 'ready' && <p className="max-w-xl text-center text-lg leading-relaxed">{item.question}</p>}

        {phase === 'loading' && <p className="text-white/60">Loading…</p>}
        {phase === 'ready' && (
          <div className="max-w-md space-y-3 text-center">
            <p className="text-lg font-semibold">A real interview, out loud</p>
            <p className="text-sm text-white/70">{INTERVIEWER} will ask {TOTAL} questions, following up on what you say. Answer out loud; when you stop talking, your turn ends. Your camera stays on and the interview is recorded (you can download it for 1 hour). Keep your phone away: a phone on camera ends the interview.</p>
            <button className="w-full rounded-xl bg-violet-600 px-4 py-3 font-semibold hover:bg-violet-500" onClick={begin}>Start interview</button>
          </div>
        )}
        {phase === 'listening' && (
          <div className="flex flex-col items-center gap-3">
            <div className="flex h-8 items-end gap-1" aria-label="Your microphone level">
              {[0.3, 0.6, 1, 0.6, 0.3].map((k, i) => <span key={i} className="w-2 rounded bg-emerald-400 transition-all" style={{ height: `${8 + level * k * 28}px` }} />)}
            </div>
            <p className="text-sm text-emerald-300">Listening… your turn ends when you stop talking</p>
            <div className="flex gap-2">
              <button className="rounded-lg bg-white/10 px-3 py-2 text-sm hover:bg-white/20" onClick={submitVoice}>I'm done</button>
              <button className="rounded-lg px-3 py-2 text-sm text-white/60 hover:text-white" onClick={typeInstead}>Type instead</button>
            </div>
          </div>
        )}
        {phase === 'typing' && (
          <div className="w-full max-w-xl space-y-2">
            <textarea className="min-h-32 w-full rounded-xl border border-white/20 bg-white/5 p-3 text-white" value={draft} maxLength={5000}
              placeholder="Type your answer" onChange={e => setDraft(e.target.value)} onPaste={() => engine.current?.markPasted()} />
            <div className="flex gap-2">
              <button className="rounded-lg bg-violet-600 px-4 py-2 font-semibold disabled:opacity-50" disabled={!draft.trim()} onClick={submitTyped}>Answer</button>
              <button className="rounded-lg px-3 py-2 text-sm text-white/60 hover:text-white" onClick={() => { engine.current?.endAnswer(); listen() }}>Speak instead</button>
            </div>
          </div>
        )}
        {phase === 'warned' && (
          <div role="alert" className="max-w-md space-y-3 rounded-xl bg-amber-500/15 p-4 text-center">
            <p className="font-semibold text-amber-300">A phone is visible on camera</p>
            <p className="text-sm text-white/80">Put it away. If it's seen again, the interview ends and counts as a JobPilot Policy violation.</p>
            <button className="rounded-lg bg-amber-500 px-4 py-2 font-semibold text-black" onClick={() => ask(session.current!)}>It's away, continue</button>
          </div>
        )}
        {phase === 'policy' && (
          <div role="alert" className="max-w-md space-y-3 rounded-xl bg-red-500/15 p-4 text-center">
            <p className="font-semibold text-red-300">Interview stopped</p>
            <p className="text-sm text-white/80">A phone was seen on camera a second time. This violates JobPilot Policy.</p>
            <button className="rounded-lg bg-white/10 px-4 py-2" onClick={() => s && finished(s)}>Close</button>
          </div>
        )}
        {phase === 'finishing' && <p className="text-white/70">{note ?? 'Writing your report…'}</p>}
        {away && phase !== 'policy' && <p role="alert" className="rounded-lg bg-amber-500/20 px-3 py-2 text-sm text-amber-200">Look at the camera. Interviewers notice when you look away.</p>}
        {error && <p role="alert" className="max-w-md rounded-lg bg-red-500/20 px-3 py-2 text-center text-sm text-red-200">{error}</p>}
        {phase === 'error' && <button className="rounded-lg bg-white/10 px-4 py-2" onClick={() => location.reload()}>Try again</button>}

        {/* the candidate's own camera, like a video call */}
        <video ref={video} muted playsInline className={`absolute bottom-4 right-4 w-28 -scale-x-100 rounded-xl border border-white/20 bg-black object-cover sm:w-40 ${away ? 'ring-4 ring-amber-500' : ''}`} />
      </main>
    </div>
  )
}
