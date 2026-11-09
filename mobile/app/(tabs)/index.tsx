import { useCallback, useEffect, useState } from 'react'
import { Pressable, Switch, View } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { api, msg, useSession, type Application, type AutoSettings } from '../../src/lib'
import { Btn, Card, ErrorText, Label, Locked, Row, Screen, T, Title, useColors } from '../../src/ui'

/** The phone's job: unblock the agent (answer its questions) and steer auto mode. */
export default function Inbox() {
  const { gaps, user } = useSession()
  const c = useColors()
  const [apps, setApps] = useState<Application[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(() => api.listApplications().then(a => { setApps(a); setError(null) }).catch(e => setError(msg(e))), [])
  useFocusEffect(useCallback(() => { if (gaps?.missing.length === 0) load() }, [gaps, load]))

  if (!gaps || gaps.missing.length) return <Locked gaps={gaps} what="Your inbox, jobs and applications" />

  const waiting = (apps ?? []).filter(a => a.status === 'NEEDS_INPUT')
  const answers = waiting.reduce((n, a) => n + a.missing, 0)
  const failed = (apps ?? []).filter(a => a.status === 'FAILED')

  return (
    <Screen refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await load(); setRefreshing(false) }}>
      <Title kicker={`Hi ${user?.fullName.split(' ')[0] ?? ''}`}>Inbox</Title>
      <ErrorText>{error}</ErrorText>

      {apps === null ? <T muted>Loading…</T> : waiting.length === 0 ? (
        <Card tone="accent">
          <T bold>Nothing is waiting on you.</T>
          <T small muted>When the agent meets a question your profile doesn't answer, it lands here.</T>
        </Card>
      ) : (
        <Card tone="warn">
          <Label>Agent is blocked</Label>
          <T bold style={{ fontSize: 22, lineHeight: 28 }}>Agent needs {answers} answer{answers === 1 ? '' : 's'}</T>
          <T small muted>Answer once; the same question on any later form is filled for you.</T>
          {waiting.map(a => (
            <Pressable key={a.id} onPress={() => router.push(`/application/${a.id}`)}
              style={({ pressed }) => ({ backgroundColor: c.card, borderRadius: 10, padding: 12, opacity: pressed ? 0.7 : 1 })}>
              <Row>
                <View style={{ flex: 1 }}>
                  <T bold>{a.jobTitle ?? 'Job removed'}</T>
                  <T small muted>{a.company}</T>
                </View>
                <T bold style={{ color: c.warn }}>{a.missing} ›</T>
              </Row>
            </Pressable>
          ))}
        </Card>
      )}

      {failed.length > 0 && (
        <Card tone="danger">
          <T bold>{failed.length} application{failed.length === 1 ? '' : 's'} need attention</T>
          {failed.map(a => <T key={a.id} small onPress={() => router.push(`/application/${a.id}`)}>{a.jobTitle} · {a.company} ›</T>)}
        </Card>
      )}

      <AutoPanel onRunFinished={load} />

      <Pressable onPress={() => router.push('/interview')} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
        <Card>
          <Row><View style={{ flex: 1 }}><Label>Interview practice</Label><T bold>Rehearse for a job or role</T></View><T bold style={{ color: c.accent }}>›</T></Row>
          <T small muted>Six questions, a score per answer and a stronger version from your own facts.</T>
        </Card>
      </Pressable>
    </Screen>
  )
}

function AutoPanel({ onRunFinished }: { onRunFinished: () => void }) {
  const c = useColors()
  const [s, setS] = useState<AutoSettings | null>(null)
  const [form, setForm] = useState({ enabled: false, autoSubmit: false, minScore: 70, dailyLimit: 10 })
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const apply = (x: AutoSettings) => { setS(x); setForm({ enabled: x.enabled, autoSubmit: x.autoSubmit, minScore: x.minScore, dailyLimit: x.dailyLimit }) }
  useFocusEffect(useCallback(() => { api.getAuto().then(apply).catch(e => setError(msg(e))) }, []))
  useEffect(() => {
    if (!s?.running) return
    const t = setInterval(() => api.getAuto().then(x => { setS(x); if (!x.running) onRunFinished() }).catch(() => {}), 3000)
    return () => clearInterval(t)
  }, [s?.running])

  if (!s) return error ? <ErrorText>{error}</ErrorText> : null
  const dirty = form.enabled !== s.enabled || form.autoSubmit !== s.autoSubmit || form.minScore !== s.minScore || form.dailyLimit !== s.dailyLimit
  const sum = s.lastSummary

  async function act(fn: () => Promise<AutoSettings>) {
    setBusy(true)
    setError(null)
    try { apply(await fn()) } catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }

  return (
    <Card>
      <Row>
        <View style={{ flex: 1 }}>
          <Label>Auto mode</Label>
          <T bold style={{ fontSize: 20, lineHeight: 26 }}>{s.running ? 'Running now…' : form.enabled ? 'On' : 'Off'}</T>
        </View>
        <Switch value={form.enabled} onValueChange={v => setForm({ ...form, enabled: v })} trackColor={{ true: c.accent }} />
      </Row>
      <T small muted>Every 15 minutes the agent prepares applications for your best Greenhouse and Lever matches. {s.submittedToday} of {s.dailyLimit} submitted today.</T>

      <Stepper label="Only jobs matching at least" value={form.minScore} step={5} min={0} max={100} onChange={v => setForm({ ...form, minScore: v })} />
      <Stepper label="Most applications per day" value={form.dailyLimit} step={1} min={1} max={50} onChange={v => setForm({ ...form, dailyLimit: v })} />

      <Row style={{ alignItems: 'flex-start' }}>
        <View style={{ flex: 1, gap: 2 }}>
          <T bold>Submit for me</T>
          <T small muted>When every answer is in, the JobPilot extension in your PC's Chrome fills the form and presses Submit. Off: it fills, you press Submit. CAPTCHAs always come to you.</T>
        </View>
        <Switch value={form.autoSubmit} onValueChange={v => setForm({ ...form, autoSubmit: v })} trackColor={{ true: c.accent }} />
      </Row>

      <Row>
        <View style={{ flex: 1 }}><Btn kind="ghost" title={dirty ? 'Save settings' : 'Saved'} disabled={!dirty} busy={busy && dirty} onPress={() => act(() => api.saveAuto(form))} /></View>
        <View style={{ flex: 1 }}><Btn title={s.running ? 'Running…' : 'Run now'} disabled={s.running || dirty} busy={busy && !dirty} onPress={() => act(api.runAuto)} /></View>
      </Row>
      {dirty && <T small muted>Save your settings before running.</T>}
      <ErrorText>{error}</ErrorText>

      {sum && (
        <View style={{ backgroundColor: c.bg, borderRadius: 10, padding: 12, gap: 4 }}>
          <Label>Last run · {new Date(sum.at).toLocaleString()}</Label>
          <T small>
            {sum.prepared} prepared, {sum.needYourAnswers} need your answers, {sum.submitted} submitted
            {sum.waitingForYou > 0 ? `, ${sum.waitingForYou} waiting for you` : ''}{sum.failed > 0 ? `, ${sum.failed} failed` : ''}.
          </T>
          {sum.notes.map((n, i) => <T key={i} small muted>• {n}</T>)}
        </View>
      )}
    </Card>
  )
}

function Stepper({ label, value, step, min, max, onChange }: { label: string; value: number; step: number; min: number; max: number; onChange: (v: number) => void }) {
  return (
    <Row>
      <T style={{ flex: 1 }}>{label}</T>
      <Btn small kind="ghost" title="−" disabled={value <= min} onPress={() => onChange(Math.max(min, value - step))} />
      <T bold style={{ minWidth: 34, textAlign: 'center' }}>{value}</T>
      <Btn small kind="ghost" title="+" disabled={value >= max} onPress={() => onChange(Math.min(max, value + step))} />
    </Row>
  )
}
