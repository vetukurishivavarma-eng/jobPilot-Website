import { useState } from 'react'
import { View } from 'react-native'
import { api, ApiError, msg, serverAddress, setServer, setToken, useSession } from '../src/lib'
import { Btn, Chip, ErrorText, Input, Row, Screen, T, Title } from '../src/ui'

export default function SignIn() {
  const { setUser } = useSession()
  const [register, setRegister] = useState(false)
  const [server, setServerText] = useState(serverAddress())
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<{ text: string; fields?: Record<string, string> } | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit() {
    if (!server.trim()) return setError({ text: 'Enter the address of the PC running JobPilot.' })
    setBusy(true)
    setError(null)
    try {
      await setServer(server)
      setServerText(serverAddress())
      const res = register ? await api.register(email.trim(), password, fullName.trim()) : await api.login(email.trim(), password)
      await setToken(res.token)
      setUser(res.user)
    } catch (e) {
      setError({ text: msg(e), fields: e instanceof ApiError ? e.fields : undefined })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen>
      <View style={{ marginTop: 24, marginBottom: 8, gap: 8 }}>
        <Title kicker="JobPilot">{register ? 'Create your account' : 'Welcome back'}</Title>
        <T muted>Your agent finds jobs and fills applications on your PC. The phone answers its questions and keeps it moving.</T>
      </View>
      <Row>
        <Chip label="Sign in" on={!register} onPress={() => setRegister(false)} />
        <Chip label="Create account" on={register} onPress={() => setRegister(true)} />
      </Row>
      <Input label="Server address" value={server} onChangeText={setServerText} placeholder="http://192.168.x.x:8080"
        autoCapitalize="none" autoCorrect={false} keyboardType="url"
        hint="The PC running JobPilot, on the same Wi-Fi. On the PC, run ipconfig and use its IPv4 address with :8080." />
      {register && <Input label="Full name" value={fullName} onChangeText={setFullName} autoComplete="name" maxLength={120} error={error?.fields?.fullName} />}
      <Input label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" keyboardType="email-address" autoComplete="email" error={error?.fields?.email} />
      <Input label="Password" value={password} onChangeText={setPassword} secureTextEntry maxLength={72}
        autoComplete={register ? 'new-password' : 'current-password'} hint={register ? 'At least 8 characters.' : undefined} error={error?.fields?.password} />
      {error && !error.fields && <ErrorText>{error.text}</ErrorText>}
      <Btn title={register ? 'Create account' : 'Sign in'} onPress={submit} busy={busy} />
    </Screen>
  )
}
