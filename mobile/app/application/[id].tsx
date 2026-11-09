import { useEffect, useState } from 'react'
import { Alert, View } from 'react-native'
import { router, Stack, useLocalSearchParams } from 'expo-router'
import * as WebBrowser from 'expo-web-browser'
import { api, msg, STATUS, type Application, type FormField } from '../../src/lib'
import { Btn, Card, Chip, ErrorText, Footer, Input, Label, Loading, Row, Screen, T, Tag, useColors } from '../../src/ui'

const SOURCE: Record<string, [string, 'muted' | 'warn' | 'accent']> = {
  PROFILE: ['from your profile', 'muted'],
  BANK: ['you answered this before', 'muted'],
  AI: ['AI draft: check it', 'warn'],
  USER: ['your answer', 'accent'],
  LETTER: ['your cover letter for this job', 'accent'],
}

/** One application: answer what the agent couldn't (required + empty first), see the rest with where each value came from. */
export default function ApplicationScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const [a, setA] = useState<Application | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => { api.getApplication(id).then(setA).catch(e => setError(msg(e))) }, [id])
  if (!a) return error ? <Screen header><ErrorText>{error}</ErrorText></Screen> : <Loading />

  const locked = a.status === 'FILLING' || a.status === 'SUBMITTED'
  const value = (f: FormField) => draft[f.key] ?? f.value ?? ''
  const dirty = Object.keys(draft).length > 0
  const todo = a.fields.filter(f => f.required && !f.value)
  const rest = a.fields.filter(f => !(f.required && !f.value))
  const field = (f: FormField) => <Field key={f.key} f={f} value={value(f)} disabled={locked} onChange={v => setDraft({ ...draft, [f.key]: v })} />

  async function save() {
    setSaving(true)
    setError(null)
    try { setA(await api.saveAnswers(a!.id, draft)); setDraft({}) } catch (e) { setError(msg(e)) } finally { setSaving(false) }
  }
  function remove() {
    Alert.alert('Delete this application draft?', 'Answers you gave stay remembered.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => api.deleteApplication(a!.id).then(() => router.back()).catch(e => setError(msg(e))) },
    ])
  }

  return (
    <Screen header footer={!locked && (
      <Footer>
        <Btn kind="danger" small title="Delete" onPress={remove} />
        <T small muted style={{ flex: 1 }}>{dirty ? 'Unsaved answers' : a.missing > 0 ? `${a.missing} required missing` : 'All answers in'}</T>
        <Btn title="Save answers" disabled={!dirty} busy={saving} onPress={save} />
      </Footer>
    )}>
      <Stack.Screen options={{ title: a.company ?? 'Application' }} />
      <View style={{ gap: 6 }}>
        <T bold style={{ fontSize: 24, lineHeight: 30 }}>{a.jobTitle ?? 'Job removed'}</T>
        <Row wrap>
          <Tag label={STATUS[a.status][0]} tone={STATUS[a.status][1]} />
          {a.auto && <Tag label="auto" />}
          {a.jobUrl && <T small muted onPress={() => WebBrowser.openBrowserAsync(a.jobUrl!)}>View posting ›</T>}
        </Row>
      </View>
      <ErrorText>{error}</ErrorText>
      {a.error && <Card tone="danger"><T small>{a.error}</T></Card>}
      {a.status === 'SUBMITTED' && <Card tone="accent"><T bold>Applied ✓</T><T small>{a.submittedAt ? `Confirmed ${new Date(a.submittedAt).toLocaleString()}` : 'The site confirmed your application.'}</T></Card>}
      {a.status === 'READY' && !dirty && (
        <Card tone="accent"><T small>Every required answer is in. The JobPilot extension in your PC’s Chrome fills this form on its next run (auto mode), or press Open and fill on the website.</T></Card>
      )}

      {todo.length > 0 && (
        <Card tone="warn">
          <T bold style={{ fontSize: 18 }}>The agent needs {todo.length} answer{todo.length === 1 ? '' : 's'}</T>
          <T small muted>Your profile doesn’t cover these. Answer once and every future form reuses it.</T>
          {todo.map(field)}
        </Card>
      )}
      {rest.length > 0 && <Card><Label>Everything else on the form</Label>{rest.map(field)}</Card>}
    </Screen>
  )
}

function Field({ f, value, onChange, disabled }: { f: FormField; value: string; onChange: (v: string) => void; disabled: boolean }) {
  const c = useColors()
  const [filter, setFilter] = useState('')
  const src = f.source && SOURCE[f.source]
  const label = f.label + (f.required ? ' *' : '')
  const tag = src && <T small style={{ color: src[1] === 'warn' ? c.warn : src[1] === 'accent' ? c.accent : c.muted }}>{src[0]}</T>

  if (f.type === 'TEXT' || f.type === 'TEXTAREA')
    return <View style={{ gap: 4 }}><Input label={label} value={value} editable={!disabled} multiline={f.type === 'TEXTAREA'} maxLength={10000} onChangeText={onChange} />{tag}</View>

  if (f.type === 'FILE')
    return <View style={{ gap: 4 }}><Label>{label}</Label><T>{value ? `📎 ${value}` : 'No resume. Upload one in Profile.'}</T>{tag}</View>

  // SELECT / MULTISELECT as chips; long lists (countries...) get a filter box
  const multi = f.type === 'MULTISELECT'
  const picked = multi ? (value ? value.split(', ') : []) : [value]
  const shown = f.options.length > 12 ? f.options.filter(o => o.toLowerCase().includes(filter.toLowerCase())).slice(0, 30) : f.options
  return (
    <View style={{ gap: 6 }}>
      <Label>{label}</Label>
      {f.options.length > 12 && <Input label="Filter" value={filter} onChangeText={setFilter} placeholder={value || 'Type to filter'} />}
      <Row wrap gap={6}>
        {shown.map(o => (
          <Chip key={o} label={o} on={picked.includes(o)} disabled={disabled}
            onPress={() => onChange(multi ? (picked.includes(o) ? picked.filter(x => x !== o) : [...picked, o]).join(', ') : o)} />
        ))}
      </Row>
      {tag}
    </View>
  )
}
