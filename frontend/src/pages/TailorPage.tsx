import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, type Choice, type Job, type Outreach, type SectionPick, type TailoredDoc } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

/** Per-job tailored resume (original vs tailored, section by section) and cover letter. */
export default function TailorPage() {
  const { id } = useParams()
  const [job, setJob] = useState<Job | null>(null)
  const [resume, setResume] = useState<TailoredDoc | null>(null)
  const [letter, setLetter] = useState<TailoredDoc | null>(null)
  const [tab, setTab] = useState<'resume' | 'letter' | 'outreach'>('resume')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.getJob(id!).then(setJob).catch(e => setError(msg(e)))
    api.jobDocuments(id!).then(d => { setResume(d.resume); setLetter(d.letter) }).catch(e => setError(msg(e)))
  }, [id])

  if (!job) return error ? <p className="text-danger">{error}</p> : <p className="text-muted">Loading…</p>

  return (
    <div className="space-y-6">
      <div>
        <Link to="/jobs" className="text-sm text-muted hover:text-ink">← Jobs</Link>
        <h1 className="mt-2 font-display text-3xl font-bold">Tailor for {job.company}</h1>
        <p className="text-sm text-muted">{job.title}. Everything here comes from your profile and resume. Nothing is made up, and you choose what to keep.</p>
      </div>
      <div className="flex gap-1 rounded-lg border border-line bg-white p-1 w-fit" role="tablist">
        {(['resume', 'letter', 'outreach'] as const).map(t => (
          <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
            className={`rounded-md px-3 py-1 text-sm font-medium ${tab === t ? 'bg-ink text-paper' : 'text-muted hover:text-ink'}`}>
            {t === 'resume' ? 'Resume' : t === 'letter' ? 'Cover letter' : 'Message recruiter'}
          </button>
        ))}
      </div>
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      {tab === 'resume' ? <ResumeEditor jobId={job.id} doc={resume} onChange={setResume} onError={setError} />
        : tab === 'letter' ? <LetterEditor jobId={job.id} doc={letter} onChange={setLetter} onError={setError} />
        : <OutreachPanel jobId={job.id} onError={setError} />}
    </div>
  )
}

function Generate({ label, busy, onClick, hint }: { label: string; busy: boolean; onClick: () => void; hint: string }) {
  return (
    <div className="card text-center">
      <button className="btn" onClick={onClick} disabled={busy}>{busy ? 'Working… (local AI can take a minute)' : label}</button>
      <p className="mt-2 text-xs text-muted">{hint}</p>
    </div>
  )
}

function Downloads({ id, onError }: { id: string; onError: (m: string) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {(['pdf', 'docx', 'txt'] as const).map(f => (
        <button key={f} className="btn-ghost" onClick={() => api.downloadDocument(id, f).catch(e => onError(msg(e)))}>
          Download {f.toUpperCase()}
        </button>
      ))}
    </div>
  )
}

function ResumeEditor({ jobId, doc, onChange, onError }: { jobId: string; doc: TailoredDoc | null; onChange: (d: TailoredDoc) => void; onError: (m: string | null) => void }) {
  const [busy, setBusy] = useState(false)
  const [picks, setPicks] = useState<Record<string, SectionPick>>({})
  const [saved, setSaved] = useState(false)

  async function generate() {
    setBusy(true); onError(null)
    try { onChange(await api.tailorResume(jobId)); setPicks({}) } catch (e) { onError(msg(e)) } finally { setBusy(false) }
  }

  if (!doc?.resume) return <Generate label="Tailor my resume for this job" busy={busy} onClick={generate}
    hint="Skills this job wants go first, your most relevant experience leads, and AI rewording is fact-checked against your profile." />

  const r = doc.resume
  const pick = (key: string, c: Choice): SectionPick => picks[key] ?? { useTailored: c.useTailored }
  const set = (key: string, c: Choice, p: Partial<SectionPick>) => { setPicks({ ...picks, [key]: { ...pick(key, c), ...p } }); setSaved(false) }
  const dirty = Object.keys(picks).length > 0

  async function save() {
    onError(null)
    try {
      onChange(await api.saveResumeChoices(doc!.id, {
        summary: pick('summary', r.summary), skills: pick('skills', r.skills),
        experiences: r.experiences.map((e, i) => pick('exp' + i, e.bullets)),
      }))
      setPicks({}); setSaved(true)
    } catch (e) { onError(msg(e)) }
  }

  const sections: [string, string, Choice][] = [
    ['summary', 'Summary', r.summary], ['skills', 'Skills', r.skills],
    ...r.experiences.map((e, i): [string, string, Choice] => ['exp' + i, e.heading, e.bullets]),
  ]

  return (
    <div className="space-y-4 pb-20">
      {r.notes.length > 0 && (
        <div className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <p className="font-medium">Fact check</p>
          <ul className="list-disc pl-5 text-xs">{r.notes.map((n, i) => <li key={i}>{n} Your original was kept.</li>)}</ul>
        </div>
      )}
      {sections.map(([key, title, c]) => <Section key={key} title={title} c={c} p={pick(key, c)} onPick={p => set(key, c, p)} />)}

      <div className="fixed inset-x-0 bottom-0 border-t border-line bg-paper/95 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-3">
          <button className="btn-ghost" onClick={generate} disabled={busy}>{busy ? 'Working…' : 'Start over'}</button>
          <span className="mr-auto text-sm text-muted">{dirty ? 'Unsaved choices' : saved ? 'Saved. Downloads and applications use this version.' : ''}</span>
          <button className="btn" onClick={save} disabled={!dirty}>Save choices</button>
          {!dirty && <Downloads id={doc.id} onError={onError} />}
        </div>
      </div>
    </div>
  )
}

function Section({ title, c, p, onPick }: { title: string; c: Choice; p: SectionPick; onPick: (p: Partial<SectionPick>) => void }) {
  const tailored = p.text ?? c.tailored
  if (tailored == null)
    return (
      <section className="card">
        <h3 className="font-display font-bold">{title}</h3>
        <p className="mt-1 whitespace-pre-wrap text-sm">{c.original || <span className="text-muted">Empty</span>}</p>
        <p className="mt-1 text-xs text-muted">Already a good fit. Unchanged.</p>
      </section>
    )
  return (
    <section className="card space-y-3">
      <div className="flex flex-wrap items-baseline gap-2">
        <h3 className="mr-auto font-display font-bold">{title}</h3>
        {c.why && <span className="text-xs text-muted">{c.why}</span>}
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <button type="button" onClick={() => onPick({ useTailored: false })} aria-pressed={!p.useTailored}
          className={`rounded-lg border-2 p-3 text-left ${!p.useTailored ? 'border-ink' : 'border-line opacity-70 hover:opacity-100'}`}>
          <p className="label">Your original {!p.useTailored && '✓'}</p>
          <p className="whitespace-pre-wrap text-sm">{c.original || <span className="text-muted">Empty</span>}</p>
        </button>
        <div className={`rounded-lg border-2 p-3 ${p.useTailored ? 'border-signal' : 'border-line'}`}>
          <button type="button" className="label text-left" onClick={() => onPick({ useTailored: true })} aria-pressed={p.useTailored}>
            Tailored {p.useTailored ? '✓' : '(click to use)'}
          </button>
          <textarea className="mt-1 w-full resize-y bg-transparent text-sm outline-none" rows={Math.min(10, tailored.split('\n').length + 1)}
            value={tailored} aria-label={`Tailored ${title}`} onChange={e => onPick({ text: e.target.value, useTailored: true })} />
        </div>
      </div>
    </section>
  )
}

function LetterEditor({ jobId, doc, onChange, onError }: { jobId: string; doc: TailoredDoc | null; onChange: (d: TailoredDoc) => void; onError: (m: string | null) => void }) {
  const [busy, setBusy] = useState(false)
  const [text, setText] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  async function generate() {
    setBusy(true); onError(null)
    try { onChange(await api.writeLetter(jobId)); setText(null) } catch (e) { onError(msg(e)) } finally { setBusy(false) }
  }
  if (!doc?.letter) return <Generate label="Write a cover letter" busy={busy} onClick={generate}
    hint="Written from your profile. Any claim the fact check can't trace to your facts is flagged for you." />

  const l = doc.letter
  const value = text ?? l.text
  async function save() {
    onError(null)
    try { onChange(await api.saveLetter(doc!.id, value)); setText(null); setSaved(true) } catch (e) { onError(msg(e)) }
  }

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted">
        {l.source === 'AI' ? 'AI draft from your profile. Read it before sending.' : l.source === 'TEMPLATE' ? 'Built from your profile (local AI wasn\'t available).' : 'Your edited version.'}
        {' '}It's also used for "cover letter" boxes when the agent applies to this job.
      </p>
      {l.warnings.length > 0 && (
        <ul className="list-disc rounded-md bg-amber-50 py-2 pl-8 pr-3 text-sm text-amber-900">{l.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
      )}
      <textarea className="field min-h-96 font-[inherit] leading-relaxed" value={value} aria-label="Cover letter"
        onChange={e => { setText(e.target.value); setSaved(false) }} />
      <div className="flex flex-wrap items-center gap-3">
        <button className="btn-ghost" onClick={generate} disabled={busy}>{busy ? 'Writing…' : 'Rewrite'}</button>
        <button className="btn" onClick={save} disabled={text === null || text === l.text}>Save</button>
        {saved && <span className="text-sm text-muted">Saved</span>}
        <span className="mr-auto" />
        {text === null && <Downloads id={doc.id} onError={onError} />}
      </div>
    </div>
  )
}

/** Draft-only recruiter outreach: a short LinkedIn note + an email. The user finds the recruiter and sends it. */
function OutreachPanel({ jobId, onError }: { jobId: string; onError: (m: string | null) => void }) {
  const [d, setD] = useState<Outreach | null>(null)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)
  async function draft() {
    setBusy(true); onError(null); setCopied(null)
    try { setD(await api.outreach(jobId)) } catch (e) { onError(msg(e)) } finally { setBusy(false) }
  }
  const copy = (what: string, text: string) => { navigator.clipboard?.writeText(text); setCopied(what) }
  if (!d) return <Generate label="Draft recruiter message" busy={busy} onClick={draft} hint="A LinkedIn note and an email, from your profile only. You send them yourself." />
  return (
    <div className="card space-y-4">
      {d.source === 'TEMPLATE' && <p className="text-xs text-muted">Template — AI was unavailable.</p>}
      <div className="space-y-2">
        <p className="label">LinkedIn note</p>
        <textarea className="field min-h-24 text-sm" readOnly value={d.dm} />
        <button className="btn-ghost" onClick={() => copy('dm', d.dm)}>{copied === 'dm' ? 'Copied' : 'Copy note'}</button>
      </div>
      <div className="space-y-2 border-t border-line pt-3">
        <p className="text-sm"><b>Subject:</b> {d.subject}</p>
        <textarea className="field min-h-40 text-sm" readOnly value={d.body} />
        <div className="flex gap-3">
          <button className="btn-ghost" onClick={() => copy('email', `Subject: ${d.subject}

${d.body}`)}>{copied === 'email' ? 'Copied' : 'Copy email'}</button>
          <a className="btn-ghost" href={`mailto:?subject=${encodeURIComponent(d.subject)}&body=${encodeURIComponent(d.body)}`}>Open in email app</a>
        </div>
      </div>
      <button className="btn-ghost" onClick={draft} disabled={busy}>{busy ? 'Working…' : 'Redraft'}</button>
    </div>
  )
}
