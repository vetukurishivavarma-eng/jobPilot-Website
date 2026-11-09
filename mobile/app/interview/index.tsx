import { useCallback, useState } from 'react'
import { Alert, Pressable, View } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { api, msg, useSession, type InterviewSummary, type Job } from '../../src/lib'
import { Btn, Card, Chip, ErrorText, Input, Label, Locked, Row, Screen, T, Tag, useColors } from '../../src/ui'

/** Mock interview coach: start from a job or a typed role; past sessions below. */
export default function Interviews() {
  const { gaps } = useSession()
  const c = useColors()
  const [jobs, setJobs] = useState<Job[]>([])
  const [history, setHistory] = useState<InterviewSummary[] | null>(null)
  const [jobId, setJobId] = useState('')
  const [role, setRole] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const unlocked = gaps?.missing.length === 0

  useFocusEffect(useCallback(() => {
    if (!unlocked) return
    api.listJobs({ status: 'ACTIVE', q: '', includeClosed: false, page: 0, sort: 'match' }).then(p => setJobs(p.items)).catch(e => setError(msg(e)))
    api.listInterviews().then(setHistory).catch(e => setError(msg(e)))
  }, [unlocked]))

  if (!unlocked) return <Locked gaps={gaps} what="Interview practice" />

  async function start() {
    setBusy(true)
    setError(null)
    try { router.push(`/interview/${(await api.startInterview(jobId ? { jobId } : { role: role.trim() })).id}`) }
    catch (e) { setError(msg(e)) } finally { setBusy(false) }
  }
  function remove(s: InterviewSummary) {
    Alert.alert('Delete this session?', s.title, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => api.deleteInterview(s.id).then(() => setHistory(h => h && h.filter(x => x.id !== s.id))).catch(e => setError(msg(e))) },
    ])
  }

  return (
    <Screen header>
      <T muted small>Six practice questions from the job and your profile. Type an answer, get a score and a stronger version built from your own facts. Practice only, not for a real interview.</T>
      <Card>
        <Label>A job from your list</Label>
        <Row wrap gap={6}>
          {jobs.slice(0, 12).map(j => (
            <Chip key={j.id} label={`${j.title} · ${j.company}`} on={jobId === j.id} onPress={() => { setJobId(jobId === j.id ? '' : j.id); setRole('') }} />
          ))}
          {jobs.length === 0 && <T small muted>No jobs in your inbox yet.</T>}
        </Row>
        <Input label="Or a role" placeholder="Backend Engineer" maxLength={120} value={role} onChangeText={v => { setRole(v); if (v) setJobId('') }} />
        <Btn kind="accent" title={busy ? 'Writing questions… (up to a minute)' : 'Start practice'} busy={busy} disabled={!jobId && !role.trim()} onPress={start} />
        <ErrorText>{error}</ErrorText>
      </Card>

      <Label>Past sessions</Label>
      {history === null ? <T muted>Loading…</T> : history.length === 0 ? <T small muted>None yet.</T> : (
        <Card style={{ padding: 0, gap: 0 }}>
          {history.map((s, i) => (
            <Pressable key={s.id} onPress={() => router.push(`/interview/${s.id}`)} onLongPress={() => remove(s)}
              style={({ pressed }) => ({ padding: 14, borderTopWidth: i ? 1 : 0, borderTopColor: c.line, opacity: pressed ? 0.6 : 1 })}>
              <Row>
                <View style={{ flex: 1 }}>
                  <T bold>{s.title}</T>
                  <T small muted>{s.answered}/{s.total} answered · {new Date(s.createdAt).toLocaleDateString()}</T>
                </View>
                {s.averageScore != null && <Tag label={`avg ${s.averageScore}/5`} tone="accent" />}
              </Row>
            </Pressable>
          ))}
        </Card>
      )}
      {history && history.length > 0 && <T small muted>Long-press a session to delete it.</T>}
    </Screen>
  )
}
