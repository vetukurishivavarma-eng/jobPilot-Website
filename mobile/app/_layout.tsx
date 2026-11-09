import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, AppState, KeyboardAvoidingView } from 'react-native'
import { router, Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import * as Clipboard from 'expo-clipboard'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { api, ApiError, auth, loadStored, looksLikeJob, msg, profileGaps, SessionCtx, setToken, type Gaps, type User } from '../src/lib'
import { Loading, useColors } from '../src/ui'

export default function Root() {
  const c = useColors()
  const [user, setUser] = useState<User | null>(null)
  const [gaps, setGaps] = useState<Gaps | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    auth.onSignedOut = () => { setUser(null); setGaps(null) }
    loadStored()
      .then(({ server, token }) => (server && token ? api.me().then(setUser) : undefined))
      .catch(() => {}) // offline or expired: sign in again (the token stays if it was just offline)
      .finally(() => setLoading(false))
  }, [])

  const recheck = useCallback(async () => {
    try {
      const [p, r] = await Promise.all([api.getProfile(), api.listResumes()])
      setGaps(profileGaps(p, r))
    } catch { /* keep the last known state */ }
  }, [])
  useEffect(() => { if (user) recheck() }, [user, recheck])

  useClipboardJobs(!!user, gaps?.missing.length === 0)

  if (loading) return <Loading />
  return (
    <SafeAreaProvider>
      <SessionCtx.Provider value={{ user, setUser: u => { if (!u) setToken(null); setUser(u) }, gaps, recheck }}>
        <StatusBar style="auto" />
        {/* one keyboard wrapper for every screen (Android edge-to-edge doesn't resize by itself) */}
        <KeyboardAvoidingView behavior="padding" style={{ flex: 1, backgroundColor: c.bg }}>
          <Stack screenOptions={{
            headerShadowVisible: false, headerStyle: { backgroundColor: c.bg }, headerTintColor: c.ink,
            headerTitleStyle: { fontWeight: '700' }, contentStyle: { backgroundColor: c.bg },
          }}>
            <Stack.Protected guard={!user}>
              <Stack.Screen name="sign-in" options={{ headerShown: false }} />
            </Stack.Protected>
            <Stack.Protected guard={!!user}>
              <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
              <Stack.Screen name="job/[id]" options={{ title: 'Job' }} />
              <Stack.Screen name="tailor/[id]" options={{ title: 'Tailor' }} />
              <Stack.Screen name="application/[id]" options={{ title: 'Application' }} />
              <Stack.Screen name="add-job" options={{ title: 'Add a job', presentation: 'modal' }} />
              <Stack.Screen name="interview/index" options={{ title: 'Interview practice' }} />
              <Stack.Screen name="interview/[id]" options={{ title: 'Practice' }} />
            </Stack.Protected>
          </Stack>
        </KeyboardAvoidingView>
      </SessionCtx.Provider>
    </SafeAreaProvider>
  )
}

/** Copied a job link anywhere (LinkedIn, WhatsApp, Chrome)? Opening JobPilot offers to add it. */
function useClipboardJobs(signedIn: boolean, unlocked: boolean) {
  const offered = useRef(new Set<string>())
  useEffect(() => {
    if (!signedIn) return
    async function check() {
      const text = (await Clipboard.getStringAsync().catch(() => '')).trim()
      if (!looksLikeJob(text) || offered.current.has(text)) return
      offered.current.add(text)
      Alert.alert('Add this job?', text, [
        { text: 'Not now', style: 'cancel' },
        { text: 'Add', onPress: () => add(text) },
      ])
    }
    async function add(url: string) {
      try {
        const j = await api.importJob(url)
        Alert.alert('Added', `${j.title} at ${j.company}`, unlocked
          ? [{ text: 'OK' }, { text: 'Open', onPress: () => router.push(`/job/${j.id}`) }]
          : [{ text: 'OK' }])
      } catch (e) {
        // the site blocks reading (LinkedIn, Naukri): let the user paste the description instead
        if (e instanceof ApiError && e.status === 422) router.push({ pathname: '/add-job', params: { url } })
        else Alert.alert("Couldn't add it", msg(e))
      }
    }
    check()
    const sub = AppState.addEventListener('change', st => { if (st === 'active') check() })
    return () => sub.remove()
  }, [signedIn, unlocked])
}
