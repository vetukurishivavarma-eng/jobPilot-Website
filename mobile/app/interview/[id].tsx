import { useEffect, useState } from 'react'
import { Pressable, View } from 'react-native'
import { Stack, useLocalSearchParams } from 'expo-router'
import { api, msg, type InterviewSession } from '../../src/lib'
import { Btn, Card, ErrorText, Input, Label, Loading, Row, Screen, T, Tag, useColors } from '../../src/ui'

const TYPE = { BEHAVIOURAL: 'Behavioural', TECHNICAL: 'Technical', ROLE: 'Role' } as const
const RUBRIC = [['relevance', 'Relevance'], ['specificity', 'Specificity'], ['technical', 'Technical'], ['concision', 'Concision']] as const

/** One question at a time; drafts survive a failed scoring (e.g. 503 AI_UNAVAILABLE) so the user can retry. */
export default function InterviewSessionScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const c = useColors()
  const [s, setS] = useState<InterviewSession | null>(null)
  const [i, setI] = useState(0)
  const [drafts, setDrafts] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.getInterview(id).then(x => { setS(x); const next = x.items.findIndex(q => !q.answer); setI(next < 0 ? 0 : next) }).catch(e => setError(msg(e)))
  }, [id])
  if (!s) return error ? <Screen header><ErrorText>{error}</ErrorText></Screen> : <Loading />

  const item = s.items[i]
  const draft = drafts[i] ?? item.answer ?? ''
  const fb = item.feedback

  async function submit() {
    setBusy(true)
    setError(null)
    try { setS(await api.answerInterview(s!.id, i, draft)); setDrafts(d => { const { [i]: _, ...rest } = d; return rest }) }
    catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }

  return (
    <Screen header>
      <Stack.Screen options={{ title: s.title }} />
      {s.source === 'TEMPLATE' && <T small muted>Standard questions (the local AI wasn't available to write ones for this job).</T>}
      <Row wrap gap={6}>
        {s.items.map((q, n) => (
          <Pressable key={n} onPress={() => { setI(n); setError(null) }} accessibilityState={{ selected: n === i }}
            style={{ minWidth: 44, alignItems: 'center', paddingVertical: 8, paddingHorizontal: 10, borderRadius: 10, borderWidth: 1,
              borderColor: n === i ? c.ink : q.answer ? c.accent : c.line, backgroundColor: n === i ? c.ink : q.answer ? c.accentSoft : c.card }}>
            <T small bold style={{ color: n === i ? c.bg : q.answer ? c.accent : c.muted }}>{n + 1}{q.feedback?.score != null ? ` · ${q.feedback.score}` : ''}</T>
          </Pressable>
        ))}
      </Row>

      <Card>
        <Label>{TYPE[item.type]} · {i + 1} of {s.items.length}</Label>
        <T bold style={{ fontSize: 19, lineHeight: 26 }}>{item.question}</T>
        <Input label="Your answer" multiline maxLength={5000} value={draft} style={{ minHeight: 160 }}
          placeholder="Answer as you would out loud. Behavioural: situation, task, action, result."
          onChangeText={v => setDrafts({ ...drafts, [i]: v })} />
        <ErrorText>{error}</ErrorText>
        <Row>
          <View style={{ flex: 1 }}>
            <Btn title={busy ? 'Scoring…' : item.answer ? 'Score again' : 'Get feedback'} busy={busy} disabled={!draft.trim() || draft === item.answer} onPress={submit} />
          </View>
          {i + 1 < s.items.length && <Btn kind="ghost" title="Next ›" onPress={() => { setI(i + 1); setError(null) }} />}
        </Row>
      </Card>

      {fb && (
        <Card>
          <Row><T bold style={{ flex: 1, fontSize: 18 }}>Feedback</T>{fb.score != null && <Tag label={`${fb.score}/5`} tone="accent" />}</Row>
          <Row gap={6}>
            {RUBRIC.map(([k, label]) => (
              <View key={k} style={{ flex: 1, borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 8 }}>
                <T small muted style={{ fontSize: 10 }}>{label}</T>
                <T bold>{fb.rubric[k] != null ? `${fb.rubric[k]}/5` : '–'}</T>
              </View>
            ))}
          </Row>
          {fb.improvements.length > 0 && <View style={{ gap: 4 }}><Label>To improve</Label>{fb.improvements.map((x, n) => <T key={n} small>• {x}</T>)}</View>}
          {fb.improvedAnswer && (
            <View style={{ gap: 4 }}>
              <Label>A stronger answer, from your own facts</Label>
              <View style={{ backgroundColor: c.bg, borderRadius: 8, padding: 10 }}><T small selectable>{fb.improvedAnswer}</T></View>
            </View>
          )}
          {fb.warnings.length > 0 && <Card tone="warn">{fb.warnings.map((w, n) => <T key={n} small>• {w}</T>)}</Card>}
        </Card>
      )}
    </Screen>
  )
}
