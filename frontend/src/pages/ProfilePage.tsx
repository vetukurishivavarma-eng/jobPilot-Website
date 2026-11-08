import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError, profileChanged, type Education, type Experience, type Profile } from '../api'

const emptyExp: Experience = { company: '', title: '', location: null, startDate: '', endDate: null, description: null }
const emptyEdu: Education = { institution: '', degree: '', field: null, startYear: null, endYear: null, grade: null }

const toList = (s: string) => s.split(',').map(x => x.trim()).filter(Boolean)
const num = (s: string) => (s === '' ? null : Number(s))

export default function ProfilePage() {
  const [p, setP] = useState<Profile | null>(null)
  // Comma-separated text while editing; split into arrays on save.
  const [lists, setLists] = useState({ skills: '', desiredRoles: '', desiredLocations: '' })
  const [error, setError] = useState<ApiError | null>(null)
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)
  const [importing, setImporting] = useState(false)
  const [notes, setNotes] = useState<string[] | null>(null)
  const [params, setParams] = useSearchParams()
  const autoImport = useRef(params.has('import')) // ref: StrictMode runs effects twice; one ~50s model call is enough

  useEffect(() => {
    api.getProfile().then(load).catch(setError)
    if (autoImport.current) { autoImport.current = false; setParams({}, { replace: true }); fillFromResume() }
  }, [])

  async function fillFromResume() {
    setImporting(true)
    setError(null)
    try {
      const r = await api.importProfile()
      load(r.profile)
      setNotes(r.notes)
      setSaved(false)
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'NETWORK', 'Cannot reach the server'))
    } finally {
      setImporting(false)
    }
  }

  function load(x: Profile) {
    setP(x)
    setLists({ skills: x.skills.join(', '), desiredRoles: x.desiredRoles.join(', '), desiredLocations: x.desiredLocations.join(', ') })
  }

  if (!p) return error ? <p className="text-danger">{error.message}</p> : <p className="text-muted">Loading profile…</p>

  const set = <K extends keyof Profile>(k: K, v: Profile[K]) => { setP({ ...p, [k]: v }); setSaved(false) }
  const text = (k: keyof Profile) => ({
    value: (p[k] as string | null) ?? '',
    onChange: (e: { target: { value: string } }) => set(k, e.target.value as never),
  })
  const setExp = (i: number, patch: Partial<Experience>) =>
    set('experiences', p.experiences.map((e, j) => (j === i ? { ...e, ...patch } : e)))
  const setEdu = (i: number, patch: Partial<Education>) =>
    set('educations', p.educations.map((e, j) => (j === i ? { ...e, ...patch } : e)))

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!p) return
    setBusy(true)
    setError(null)
    try {
      load(await api.saveProfile({
        ...p,
        skills: toList(lists.skills), desiredRoles: toList(lists.desiredRoles), desiredLocations: toList(lists.desiredLocations),
        salaryCurrency: p.salaryCurrency?.toUpperCase() || null,
      }))
      setSaved(true)
      setNotes(null)
      profileChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'NETWORK', 'Cannot reach the server'))
    } finally {
      setBusy(false)
    }
  }

  const fe = (k: string) => error?.fields?.[k] && <p className="mt-1 text-xs text-danger">{error.fields[k]}</p>

  return (
    <form onSubmit={save} className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-bold">Your profile</h1>
          <p className="mt-1 max-w-xl text-sm text-muted">
            This is the only information the agent may use when it answers application questions. Keep it accurate. It never invents anything.
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <button type="button" className="btn-ghost" disabled={importing} onClick={fillFromResume}>
            {importing ? 'Reading your resume… (up to a minute)' : 'Fill from my resume'}
          </button>
          {p.updatedAt && <span className="text-xs text-muted">Last saved {new Date(p.updatedAt).toLocaleString()}</span>}
        </div>
      </div>

      {notes && (
        <div role="status" className="rounded-md border border-signal/40 bg-signal/10 px-4 py-3 text-sm">
          <b>Filled from your resume. Check everything, then press Save profile.</b> Nothing is saved until you do.
          {notes.length > 0 && <ul className="mt-2 list-disc pl-5 text-muted">{notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
        </div>
      )}

      <Section title="Basics">
        <Field label="Headline" span>{<input className="field" maxLength={200} placeholder="e.g. Backend engineer, Java & Spring" {...text('headline')} />}</Field>
        <Field label="Current title"><input className="field" maxLength={120} {...text('currentTitle')} /></Field>
        <Field label="Years of experience">
          <input className="field" type="number" min={0} max={60} step={0.5} value={p.yearsExperience ?? ''} onChange={e => set('yearsExperience', num(e.target.value))} />
        </Field>
        <Field label="Phone"><input className="field" type="tel" maxLength={30} {...text('phone')} />{fe('phone')}</Field>
        <Field label="Current location"><input className="field" maxLength={120} {...text('location')} /></Field>
        <Field label="Summary" span><textarea className="field min-h-28" maxLength={5000} {...text('summary')} /></Field>
      </Section>

      <Section title="What you're looking for">
        <Field label="Skills (comma separated)" span>
          <input className="field" value={lists.skills} onChange={e => { setLists({ ...lists, skills: e.target.value }); setSaved(false) }} placeholder="Java, Spring Boot, PostgreSQL" />
        </Field>
        <Field label="Desired roles (comma separated)">
          <input className="field" value={lists.desiredRoles} onChange={e => { setLists({ ...lists, desiredRoles: e.target.value }); setSaved(false) }} />
        </Field>
        <Field label="Preferred locations (comma separated)">
          <input className="field" value={lists.desiredLocations} onChange={e => { setLists({ ...lists, desiredLocations: e.target.value }); setSaved(false) }} placeholder="Bengaluru, Remote" />
        </Field>
        <Field label="Expected salary (per year)">
          <div className="flex gap-2">
            <input className="field w-24" maxLength={3} placeholder="INR" {...text('salaryCurrency')} />
            <input className="field" type="number" min={0} value={p.expectedSalary ?? ''} onChange={e => set('expectedSalary', num(e.target.value))} />
          </div>
          {fe('salaryCurrency')}
        </Field>
        <Field label="Notice period (days)">
          <input className="field" type="number" min={0} max={365} value={p.noticePeriodDays ?? ''} onChange={e => set('noticePeriodDays', num(e.target.value))} />
        </Field>
        <Field label="Work authorization"><input className="field" maxLength={200} placeholder="e.g. Indian citizen" {...text('workAuthorization')} /></Field>
        <Field label="Relocation">
          <label className="flex items-center gap-2 py-2 text-sm">
            <input type="checkbox" className="size-4 accent-signal" checked={p.willingToRelocate} onChange={e => set('willingToRelocate', e.target.checked)} />
            Willing to relocate
          </label>
        </Field>
      </Section>

      <Section title="Links">
        <Field label="LinkedIn"><input className="field" type="url" placeholder="https://" {...text('linkedinUrl')} />{fe('linkedinUrl')}</Field>
        <Field label="GitHub"><input className="field" type="url" placeholder="https://" {...text('githubUrl')} />{fe('githubUrl')}</Field>
        <Field label="Portfolio"><input className="field" type="url" placeholder="https://" {...text('portfolioUrl')} />{fe('portfolioUrl')}</Field>
      </Section>

      <Repeater title="Experience" onAdd={() => set('experiences', [...p.experiences, { ...emptyExp }])} addLabel="Add a job">
        {p.experiences.map((x, i) => (
          <Item key={i} onRemove={() => set('experiences', p.experiences.filter((_, j) => j !== i))}>
            <Field label="Company"><input className="field" required maxLength={160} value={x.company} onChange={e => setExp(i, { company: e.target.value })} /></Field>
            <Field label="Title"><input className="field" required maxLength={160} value={x.title} onChange={e => setExp(i, { title: e.target.value })} /></Field>
            <Field label="Start date"><input className="field" type="date" required value={x.startDate} onChange={e => setExp(i, { startDate: e.target.value })} /></Field>
            <Field label="End date (blank = current)"><input className="field" type="date" value={x.endDate ?? ''} onChange={e => setExp(i, { endDate: e.target.value || null })} /></Field>
            <Field label="Location"><input className="field" maxLength={120} value={x.location ?? ''} onChange={e => setExp(i, { location: e.target.value || null })} /></Field>
            <Field label="What you did" span><textarea className="field min-h-20" maxLength={5000} value={x.description ?? ''} onChange={e => setExp(i, { description: e.target.value || null })} /></Field>
          </Item>
        ))}
      </Repeater>

      <Repeater title="Education" onAdd={() => set('educations', [...p.educations, { ...emptyEdu }])} addLabel="Add education">
        {p.educations.map((x, i) => (
          <Item key={i} onRemove={() => set('educations', p.educations.filter((_, j) => j !== i))}>
            <Field label="Institution"><input className="field" required maxLength={200} value={x.institution} onChange={e => setEdu(i, { institution: e.target.value })} /></Field>
            <Field label="Degree"><input className="field" required maxLength={120} value={x.degree} onChange={e => setEdu(i, { degree: e.target.value })} /></Field>
            <Field label="Field of study"><input className="field" maxLength={120} value={x.field ?? ''} onChange={e => setEdu(i, { field: e.target.value || null })} /></Field>
            <Field label="Grade / CGPA"><input className="field" maxLength={40} value={x.grade ?? ''} onChange={e => setEdu(i, { grade: e.target.value || null })} /></Field>
            <Field label="Start year"><input className="field" type="number" min={1950} max={2100} value={x.startYear ?? ''} onChange={e => setEdu(i, { startYear: num(e.target.value) })} /></Field>
            <Field label="End year"><input className="field" type="number" min={1950} max={2100} value={x.endYear ?? ''} onChange={e => setEdu(i, { endYear: num(e.target.value) })} /></Field>
          </Item>
        ))}
      </Repeater>

      <div className="sticky bottom-0 -mx-4 flex items-center gap-4 border-t border-line bg-paper/95 px-4 py-3 backdrop-blur">
        <button className="btn" disabled={busy}>{busy ? 'Saving…' : 'Save profile'}</button>
        {saved && <span className="text-sm font-medium text-signal">Saved</span>}
        {error && <span role="alert" className="text-sm text-danger">{error.message}</span>}
      </div>
    </form>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card">
      <h2 className="mb-4 font-display text-lg font-bold">{title}</h2>
      <div className="grid gap-4 sm:grid-cols-2">{children}</div>
    </section>
  )
}

function Field({ label, span, children }: { label: string; span?: boolean; children: ReactNode }) {
  return <div className={span ? 'sm:col-span-2' : ''}><span className="label">{label}</span>{children}</div>
}

function Repeater({ title, addLabel, onAdd, children }: { title: string; addLabel: string; onAdd: () => void; children: ReactNode[] }) {
  return (
    <section className="card">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-display text-lg font-bold">{title}</h2>
        <button type="button" className="btn-ghost" onClick={onAdd}>+ {addLabel}</button>
      </div>
      {children.length ? <div className="space-y-4">{children}</div> : <p className="text-sm text-muted">Nothing added yet.</p>}
    </section>
  )
}

function Item({ onRemove, children }: { onRemove: () => void; children: ReactNode }) {
  return (
    <div className="rounded-lg border border-line p-4">
      <div className="grid gap-4 sm:grid-cols-2">{children}</div>
      <button type="button" className="mt-3 text-xs font-semibold text-danger hover:underline" onClick={onRemove}>Remove</button>
    </div>
  )
}
