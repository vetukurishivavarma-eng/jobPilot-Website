import { useEffect, useState } from 'react'
import { View } from 'react-native'
import { router, Stack, useLocalSearchParams } from 'expo-router'
import * as WebBrowser from 'expo-web-browser'
import { ago, api, msg, type Job, type JobStatus } from '../../src/lib'
import { Btn, Card, ErrorText, Label, Loading, Row, Score, Screen, T, Tag, useColors } from '../../src/ui'

const LEVEL: Record<string, string> = { INTERN: 'Internship', ENTRY: 'Entry level', MID: 'Mid level', SENIOR: 'Senior', LEAD: 'Lead / staff', MANAGER: 'Manager' }
const DEGREE: Record<string, string> = { BACHELOR: "Bachelor's", MASTER: "Master's", PHD: 'PhD' }

export default function JobScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const c = useColors()
  const [j, setJ] = useState<Job | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [preparing, setPreparing] = useState(false)

  useEffect(() => { api.getJob(id).then(setJ).catch(e => setError(msg(e))) }, [id])
  if (!j) return error ? <Screen header><ErrorText>{error}</ErrorText></Screen> : <Loading />

  const agentCan = (j.source === 'greenhouse' || j.source === 'lever') && !j.closedAt
  async function apply() {
    setPreparing(true)
    setError(null)
    try { router.replace(`/application/${(await api.createApplication(j!.id)).id}`) } catch (e) { setError(msg(e)); setPreparing(false) }
  }
  async function practice() {
    setPreparing(true)
    setError(null)
    try { router.push(`/interview/${(await api.startInterview({ jobId: j!.id })).id}`) } catch (e) { setError(msg(e)) } finally { setPreparing(false) }
  }
  async function setStatus(s: JobStatus) {
    try { await api.setJobStatus(j!.id, s); setJ({ ...j!, status: s }) } catch (e) { setError(msg(e)) }
  }

  const reqs = [
    j.minYears != null && (j.minYears === 0 ? 'Freshers welcome' : `${j.minYears}+ years`),
    j.seniority && LEVEL[j.seniority],
    j.educationLevel && `${DEGREE[j.educationLevel]} degree`,
  ].filter((x): x is string => !!x)
  const meta = [j.location, j.workplace && j.workplace[0] + j.workplace.slice(1).toLowerCase(), j.employmentType].filter(Boolean).join(' · ')

  return (
    <Screen header>
      <Stack.Screen options={{ title: j.company }} />
      <Row style={{ alignItems: 'flex-start' }} gap={14}>
        <View style={{ flex: 1, gap: 4 }}>
          <T bold style={{ fontSize: 24, lineHeight: 30 }}>{j.title}</T>
          <T><T bold>{j.company}</T>{meta ? <T muted> · {meta}</T> : null}</T>
          <T small muted>{j.closedAt ? `Closed ${ago(j.closedAt)}` : `Posted ${ago(j.postedAt ?? j.discoveredAt)}`}</T>
        </View>
        {j.match?.score != null && <Score score={j.match.score} big />}
      </Row>

      {j.match && (j.match.good.length > 0 || j.match.gaps.length > 0) && (
        <Card>
          {j.match.good.map(g => <T key={g} small><T small style={{ color: c.accent }}>✓ </T>{g}</T>)}
          {j.match.gaps.map(g => <T key={g} small><T small style={{ color: c.warn }}>! </T>{g}</T>)}
        </Card>
      )}

      {(reqs.length > 0 || j.skills.length > 0) && (
        <Row wrap gap={6}>
          {reqs.map(r => <Tag key={r} label={r} tone="ink" />)}
          {j.skills.map(s => <Tag key={s} label={s} tone="accent" />)}
        </Row>
      )}

      <ErrorText>{error}</ErrorText>
      {agentCan && j.status !== 'DISMISSED' && (
        <Btn kind="accent" title={preparing ? 'Reading the form…' : 'Apply with agent'} busy={preparing} onPress={apply} />
      )}
      <Row>
        {j.url && <View style={{ flex: 1 }}><Btn kind="ghost" title="Open posting" onPress={() => WebBrowser.openBrowserAsync(j.url!)} /></View>}
        <View style={{ flex: 1 }}><Btn kind="ghost" title="Tailor resume" onPress={() => router.push(`/tailor/${j.id}`)} /></View>
      </Row>
      <Btn kind="ghost" title="Practice interview for this job" disabled={preparing} onPress={practice} />
      <Row>
        <View style={{ flex: 1 }}>{j.status === 'SAVED'
          ? <Btn kind="ghost" title="Saved ✓" onPress={() => setStatus('NEW')} />
          : <Btn kind="ghost" title="Save" onPress={() => setStatus('SAVED')} />}</View>
        <View style={{ flex: 1 }}>{j.status === 'DISMISSED'
          ? <Btn kind="ghost" title="Restore" onPress={() => setStatus('NEW')} />
          : <Btn kind="danger" title="Dismiss" onPress={() => setStatus('DISMISSED')} />}</View>
      </Row>

      <Label>Description</Label>
      <T selectable>{j.description || 'No description.'}</T>
      <T small muted>Requirements are read from the posting automatically and may miss things. Check the original before applying.</T>
    </Screen>
  )
}
