import { useCallback, useState } from 'react'
import { Pressable, View } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { api, msg, STATUS, useSession, type Application, type ApplicationStatus } from '../../src/lib'
import { Card, ErrorText, Label, Locked, Row, Screen, T, Tag, Title, useColors } from '../../src/ui'

export default function Applications() {
  const { gaps } = useSession()
  const c = useColors()
  const [items, setItems] = useState<Application[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const unlocked = gaps?.missing.length === 0
  const load = useCallback(() => api.listApplications().then(a => { setItems(a); setError(null) }).catch(e => setError(msg(e))), [])
  useFocusEffect(useCallback(() => { if (unlocked) load() }, [unlocked, load]))

  if (!unlocked) return <Locked gaps={gaps} what="Applications" />

  return (
    <Screen refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await load(); setRefreshing(false) }}>
      <Title kicker={items ? `${items.length} total` : undefined}>Applications</Title>
      <ErrorText>{error}</ErrorText>
      {items === null ? <T muted>Loading…</T> : items.length === 0 ? (
        <Card><T muted>No applications yet. Open a Greenhouse or Lever job in Jobs and tap Apply with agent.</T></Card>
      ) : (Object.keys(STATUS) as ApplicationStatus[]).map(st => {
        const group = items.filter(a => a.status === st)
        if (!group.length) return null
        return (
          <View key={st} style={{ gap: 8 }}>
            <Label>{STATUS[st][0]} · {group.length}</Label>
            <Card style={{ padding: 0, gap: 0 }}>
              {group.map((a, i) => (
                <Pressable key={a.id} onPress={() => router.push(`/application/${a.id}`)}
                  style={({ pressed }) => ({ padding: 14, borderTopWidth: i ? 1 : 0, borderTopColor: c.line, opacity: pressed ? 0.6 : 1 })}>
                  <Row>
                    <View style={{ flex: 1 }}>
                      <T bold>{a.jobTitle ?? 'Job removed'}</T>
                      <T small muted>{a.company} · {new Date(a.updatedAt).toLocaleDateString()}{a.auto ? ' · auto' : ''}</T>
                    </View>
                    {a.missing > 0 && <Tag label={`${a.missing} to answer`} tone="warn" />}
                  </Row>
                </Pressable>
              ))}
            </Card>
          </View>
        )
      })}
    </Screen>
  )
}
