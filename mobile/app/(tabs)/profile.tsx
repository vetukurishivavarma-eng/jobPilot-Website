import { useCallback, useEffect, useState } from 'react'
import { Alert, Pressable, Switch, View } from 'react-native'
import * as DocumentPicker from 'expo-document-picker'
import { api, ApiError, msg, serverAddress, useSession, type Profile, type Resume } from '../../src/lib'
import { Bar, Btn, Card, ErrorText, Footer, Input, Label, Loading, Row, Screen, T, Tag, Title, useColors } from '../../src/ui'

const toList = (s: string) => s.split(',').map(x => x.trim()).filter(Boolean)
const num = (s: string) => (s.trim() === '' ? null : Number(s))
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

export default function ProfileScreen() {
  const { gaps, recheck, setUser, user } = useSession()
  const c = useColors()
  const [p, setP] = useState<Profile | null>(null)
  // comma lists and numbers are edited as text, converted on save
  const [text, setText] = useState({ skills: '', desiredRoles: '', desiredLocations: '', years: '', salary: '', notice: '' })
  const [resumes, setResumes] = useState<Resume[]>([])
  const [error, setError] = useState<ApiError | string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState<'save' | 'import' | 'upload' | null>(null)
  const [notes, setNotes] = useState<string[] | null>(null)

  const load = (x: Profile) => {
    setP(x)
    setText({
      skills: x.skills.join(', '), desiredRoles: x.desiredRoles.join(', '), desiredLocations: x.desiredLocations.join(', '),
      years: x.yearsExperience?.toString() ?? '', salary: x.expectedSalary?.toString() ?? '', notice: x.noticePeriodDays?.toString() ?? '',
    })
  }
  const loadResumes = useCallback(() => api.listResumes().then(setResumes).catch(e => setError(msg(e))), [])
  useEffect(() => { api.getProfile().then(load).catch(e => setError(msg(e))); loadResumes() }, [loadResumes])

  if (!p) return error ? <Screen><ErrorText>{typeof error === 'string' ? error : error.message}</ErrorText></Screen> : <Loading />

  const set = <K extends keyof Profile>(k: K, v: Profile[K]) => { setP({ ...p, [k]: v }); setDirty(true) }
  const bind = (k: 'headline' | 'currentTitle' | 'phone' | 'location' | 'summary' | 'salaryCurrency' | 'workAuthorization' | 'linkedinUrl' | 'githubUrl' | 'portfolioUrl') =>
    ({ value: p[k] ?? '', onChangeText: (v: string) => set(k, v), error: error instanceof ApiError ? error.fields?.[k] : undefined })
  const bindText = (k: keyof typeof text) => ({ value: text[k], onChangeText: (v: string) => { setText({ ...text, [k]: v }); setDirty(true) } })

  async function save() {
    setBusy('save')
    setError(null)
    try {
      load(await api.saveProfile({
        ...p!,
        skills: toList(text.skills), desiredRoles: toList(text.desiredRoles), desiredLocations: toList(text.desiredLocations),
        yearsExperience: num(text.years), expectedSalary: num(text.salary), noticePeriodDays: num(text.notice),
        salaryCurrency: p!.salaryCurrency?.trim().toUpperCase() || null,
      }))
      setDirty(false)
      setNotes(null)
      recheck()
    } catch (e) { setError(e instanceof ApiError ? e : msg(e)) } finally { setBusy(null) }
  }

  async function fillFromResume() {
    setBusy('import')
    setError(null)
    try {
      const r = await api.importProfile()
      load(r.profile)
      setNotes(r.notes)
      setDirty(true)
    } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }

  async function upload() {
    const pick = await DocumentPicker.getDocumentAsync({ type: ['application/pdf', DOCX], copyToCacheDirectory: true })
    if (pick.canceled) return
    const f = pick.assets[0]
    if ((f.size ?? 0) > 5 * 1024 * 1024) return setError('Resume must be 5 MB or smaller')
    setBusy('upload')
    setError(null)
    try {
      const fd = new FormData()
      // React Native's FormData takes a {uri, name, type} part for files
      fd.append('file', { uri: f.uri, name: f.name, type: f.mimeType ?? (f.name.endsWith('.docx') ? DOCX : 'application/pdf') } as unknown as Blob)
      const r = await api.uploadResume(fd)
      await loadResumes()
      recheck()
      setBusy(null)
      // no jobs in the profile yet: build it from this resume instead of typing it
      if (r.textExtracted && p!.experiences.length === 0) fillFromResume()
    } catch (e) { setError(msg(e)); setBusy(null) }
  }

  async function makePrimary(r: Resume) {
    try { await api.updateResume(r.id, { primary: true }); loadResumes() } catch (e) { setError(msg(e)) }
  }

  function signOut() {
    Alert.alert('Sign out?', undefined, [{ text: 'Cancel', style: 'cancel' }, { text: 'Sign out', style: 'destructive', onPress: () => setUser(null) }])
  }

  return (
    <Screen footer={dirty && (
      <Footer>
        <T small muted style={{ flex: 1 }}>{notes ? 'Filled from your resume. Check it, then save.' : 'Unsaved changes'}</T>
        <Btn title="Save profile" busy={busy === 'save'} onPress={save} />
      </Footer>
    )}>
      <Title kicker={user?.email}>Profile</Title>

      {gaps && (
        <Card tone={gaps.missing.length ? 'warn' : 'accent'}>
          <Row><T bold style={{ flex: 1 }}>{gaps.percent}% complete</T>{gaps.missing.length === 0 && <T small>Everything unlocked</T>}</Row>
          <Bar percent={gaps.percent} />
          {gaps.missing.length > 0 && <Row wrap gap={6}>{gaps.missing.map(m => <Tag key={m} label={m} tone="warn" />)}</Row>}
        </Card>
      )}
      <ErrorText>{typeof error === 'string' ? error : error?.message}</ErrorText>

      <Card>
        <Row><Label>Resumes</Label><View style={{ flex: 1 }} /><Btn small kind="ghost" title={busy === 'upload' ? 'Uploading…' : '+ Upload'} busy={busy === 'upload'} onPress={upload} /></Row>
        {resumes.length === 0 && <T small muted>Upload your resume (PDF or DOCX) and the agent fills this profile for you.</T>}
        {resumes.map(r => (
          <Pressable key={r.id} onPress={() => !r.primary && makePrimary(r)} accessibilityLabel={r.primary ? `${r.label}, default` : `Make ${r.label} the default`}>
            <Row>
              <T style={{ color: r.primary ? c.accent : c.line, fontSize: 20 }}>★</T>
              <View style={{ flex: 1 }}>
                <T bold>{r.label}</T>
                <T small muted>{r.originalFilename}{r.primary ? ' · default' : ' · tap to make default'}</T>
              </View>
              {!r.textExtracted && <Tag label="No readable text" tone="warn" />}
            </Row>
          </Pressable>
        ))}
        {resumes.length > 0 && <Btn kind="ghost" title={busy === 'import' ? 'Reading your resume… (up to 2 min)' : 'Fill from my resume'} busy={busy === 'import'} disabled={!!busy} onPress={fillFromResume} />}
      </Card>

      {notes && (
        <Card tone="accent">
          <T bold>Filled from your resume. Nothing is saved until you press Save profile.</T>
          {notes.map((n, i) => <T key={i} small muted>• {n}</T>)}
        </Card>
      )}

      <Card>
        <Label>Basics</Label>
        <Input label="Headline" placeholder="Backend engineer, Java & Spring" maxLength={200} {...bind('headline')} />
        <Input label="Current title" maxLength={120} {...bind('currentTitle')} />
        <Input label="Years of experience" keyboardType="decimal-pad" {...bindText('years')} />
        <Input label="Phone" keyboardType="phone-pad" maxLength={30} {...bind('phone')} />
        <Input label="Current location" maxLength={120} {...bind('location')} />
        <Input label="Summary" multiline maxLength={5000} {...bind('summary')} />
      </Card>

      <Card>
        <Label>What you're looking for</Label>
        <Input label="Skills (comma separated)" placeholder="Java, Spring Boot, PostgreSQL" {...bindText('skills')} />
        <Input label="Desired roles (comma separated)" {...bindText('desiredRoles')} />
        <Input label="Preferred locations (comma separated)" placeholder="Hyderabad, Remote" {...bindText('desiredLocations')} />
        <Row style={{ alignItems: 'flex-start' }}>
          <View style={{ width: 90 }}><Input label="Currency" placeholder="INR" maxLength={3} autoCapitalize="characters" {...bind('salaryCurrency')} /></View>
          <View style={{ flex: 1 }}><Input label="Expected salary / yr" keyboardType="number-pad" {...bindText('salary')} /></View>
        </Row>
        <Input label="Notice period (days)" keyboardType="number-pad" {...bindText('notice')} />
        <Input label="Work authorization" placeholder="Indian citizen" maxLength={200} {...bind('workAuthorization')} />
        <Row><T style={{ flex: 1 }}>Willing to relocate</T><Switch value={p.willingToRelocate} onValueChange={v => set('willingToRelocate', v)} trackColor={{ true: c.accent }} /></Row>
      </Card>

      <Card>
        <Label>Links</Label>
        <Input label="LinkedIn" placeholder="https://" autoCapitalize="none" keyboardType="url" {...bind('linkedinUrl')} />
        <Input label="GitHub" placeholder="https://" autoCapitalize="none" keyboardType="url" {...bind('githubUrl')} />
        <Input label="Portfolio" placeholder="https://" autoCapitalize="none" keyboardType="url" {...bind('portfolioUrl')} />
      </Card>

      {/* ponytail: experience/education are read-only here (they come from the resume import); edit them on the website */}
      <Card>
        <Label>Experience · {p.experiences.length}</Label>
        {p.experiences.length === 0 ? <T small muted>None yet. Fill from my resume adds them.</T> : p.experiences.map((x, i) => (
          <T key={i} small><T small bold>{x.title}</T> · {x.company} · {x.startDate.slice(0, 7)} – {x.endDate?.slice(0, 7) ?? 'now'}</T>
        ))}
        <Label>Education · {p.educations.length}</Label>
        {p.educations.length === 0 ? <T small muted>None yet.</T> : p.educations.map((x, i) => (
          <T key={i} small><T small bold>{x.degree}</T> · {x.institution}{x.endYear ? ` · ${x.endYear}` : ''}</T>
        ))}
        <T small muted>Edit these on the website.</T>
      </Card>

      <Card>
        <T small muted>Connected to {serverAddress()}</T>
        <Btn kind="danger" title="Sign out" onPress={signOut} />
      </Card>
    </Screen>
  )
}
