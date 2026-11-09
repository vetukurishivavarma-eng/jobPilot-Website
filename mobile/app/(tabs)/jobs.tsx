import { useCallback, useEffect, useRef, useState } from 'react'
import { FlatList, Pressable, RefreshControl, TextInput, View } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { ago, api, msg, useSession, type DiscoveryProgress, type Job, type JobStatus, type SourceResult } from '../../src/lib'
import { Btn, Card, Chip, ErrorText, Locked, Row, Score, space, T, Title, useColors } from '../../src/ui'

const LISTS = [['ACTIVE', 'Inbox'], ['SAVED', 'Saved'], ['DISMISSED', 'Dismissed'], ['ALL', 'All']] as const

export default function Jobs() {
  const { gaps } = useSession()
  const c = useColors()
  const top = useSafeAreaInsets().top
  const [status, setStatus] = useState<string>('ACTIVE')
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<'match' | 'recent'>('match')
  const [items, setItems] = useState<Job[] | null>(null)
  const [total, setTotal] = useState(0)
  const page = useRef(0)
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<DiscoveryProgress | null>(null)
  const [report, setReport] = useState<SourceResult[] | null>(null)
  const running = !!progress?.running

  const load = useCallback(async (more = false) => {
    const p = more ? page.current + 1 : 0
    try {
      const r = await api.listJobs({ status, q: query, includeClosed: false, page: p, sort })
      page.current = p
      setItems(prev => (more && prev ? [...prev, ...r.items] : r.items))
      setTotal(r.total)
      setError(null)
    } catch (e) { setError(msg(e)) }
  }, [status, query, sort])

  const unlocked = gaps?.missing.length === 0
  useFocusEffect(useCallback(() => { if (unlocked) load() }, [unlocked, load]))
  // a search keeps running on the server; pick it up again when the screen opens
  useEffect(() => { if (unlocked) api.discoveryStatus().then(p => { if (p.running) setProgress(p) }).catch(() => {}) }, [unlocked])
  useEffect(() => {
    if (!running) return
    const t = setInterval(async () => {
      try {
        const p = await api.discoveryStatus()
        if (p.done !== progress?.done) load() // new jobs appear as each company finishes
        setProgress(p)
        if (!p.running) setReport(p.results)
      } catch (e) { setError(msg(e)); setProgress(null) }
    }, 2000)
    return () => clearInterval(t)
  }, [running, progress?.done, load])

  if (!unlocked) return <Locked gaps={gaps} what="Jobs" />

  async function find() {
    setError(null)
    setReport(null)
    try { setProgress(await api.runDiscovery()) } catch (e) { setError(msg(e)) }
  }
  async function setJob(j: Job, s: JobStatus) {
    try {
      await api.setJobStatus(j.id, s)
      const stays = status === 'ALL' || status === s || (status === 'ACTIVE' && s !== 'DISMISSED')
      setItems(prev => prev && (stays ? prev.map(x => (x.id === j.id ? { ...x, status: s } : x)) : prev.filter(x => x.id !== j.id)))
    } catch (e) { setError(msg(e)) }
  }

  const added = report?.reduce((n, r) => n + r.linked, 0) ?? 0
  const header = (
    <View style={{ gap: 12, paddingTop: top + 12, marginBottom: 4 }}>
      <Row style={{ alignItems: 'flex-end' }}>
        <View style={{ flex: 1 }}><Title kicker={items ? `${total} in your locations` : undefined}>Jobs</Title></View>
        <Btn small kind="ghost" title="+ Add" onPress={() => router.push('/add-job')} />
      </Row>
      <Btn kind="accent" onPress={find} disabled={running}
        title={running ? (progress!.total ? `Reading ${progress!.done}/${progress!.total} companies…` : 'Starting…') : 'Find jobs now'} busy={running} />
      {report && (
        <Card tone="accent">
          <Row><T bold style={{ flex: 1 }}>{added} new job{added === 1 ? '' : 's'} for you</T><T muted onPress={() => setReport(null)}>Close</T></Row>
          {report.filter(r => r.error).map((r, i) => <T key={i} small muted>{r.label}: {r.error}</T>)}
        </Card>
      )}
      <TextInput value={q} onChangeText={setQ} onSubmitEditing={() => setQuery(q.trim())} returnKeyType="search"
        placeholder="Search title or company" placeholderTextColor={c.muted}
        style={{ borderWidth: 1, borderColor: c.line, backgroundColor: c.card, color: c.ink, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15 }} />
      <Row wrap>
        {LISTS.map(([k, label]) => <Chip key={k} label={label} on={status === k} onPress={() => setStatus(k)} />)}
      </Row>
      <Row>
        <T small muted>Sort</T>
        <Chip label="Best match" on={sort === 'match'} onPress={() => setSort('match')} />
        <Chip label="Newest" on={sort === 'recent'} onPress={() => setSort('recent')} />
      </Row>
      <ErrorText>{error}</ErrorText>
    </View>
  )

  return (
    <FlatList
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={{ paddingHorizontal: space, paddingBottom: 40, gap: 10 }}
      data={items ?? []}
      keyExtractor={j => j.id}
      ListHeaderComponent={header}
      keyboardShouldPersistTaps="handled"
      ListEmptyComponent={<Card><T muted>{items === null ? 'Loading…' : query ? 'No jobs match that search.' : status === 'ACTIVE'
        ? 'No openings in your preferred locations yet. Press Find jobs now, or add cities in Profile.' : 'No jobs in this list.'}</T></Card>}
      onEndReached={() => { if (items && items.length < total) load(true) }}
      onEndReachedThreshold={0.4}
      refreshControl={<RefreshControl refreshing={false} onRefresh={() => load()} colors={[c.accent]} tintColor={c.accent} />}
      renderItem={({ item: j }) => <JobRow j={j} onStatus={s => setJob(j, s)} />}
    />
  )
}

function JobRow({ j, onStatus }: { j: Job; onStatus: (s: JobStatus) => void }) {
  const c = useColors()
  const good = j.match?.good[0]
  const gap = j.match?.gaps[0]
  return (
    <Pressable onPress={() => router.push(`/job/${j.id}`)} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
      <Card>
        <Row style={{ alignItems: 'flex-start' }} gap={12}>
          {j.match?.score != null && <Score score={j.match.score} />}
          <View style={{ flex: 1, gap: 2 }}>
            <T bold style={{ fontSize: 17, lineHeight: 22 }}>{j.title}</T>
            <T small><T small bold>{j.company}</T>{j.location ? <T small muted> · {j.location}</T> : null}</T>
            <T small muted>Posted {ago(j.postedAt ?? j.discoveredAt)}</T>
          </View>
        </Row>
        {(good || gap) && (
          <View style={{ gap: 2 }}>
            {good && <T small><T small style={{ color: c.accent }}>✓ </T>{good}</T>}
            {gap && <T small><T small style={{ color: c.warn }}>! </T>{gap}</T>}
          </View>
        )}
        <Row>
          {j.status === 'SAVED'
            ? <Btn small kind="ghost" title="Saved ✓" onPress={() => onStatus('NEW')} />
            : j.status !== 'DISMISSED' && <Btn small kind="ghost" title="Save" onPress={() => onStatus('SAVED')} />}
          {j.status === 'DISMISSED'
            ? <Btn small kind="ghost" title="Restore" onPress={() => onStatus('NEW')} />
            : <Btn small kind="danger" title="Dismiss" onPress={() => onStatus('DISMISSED')} />}
        </Row>
      </Card>
    </Pressable>
  )
}
