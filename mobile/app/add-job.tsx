import { useState } from 'react'
import { router, useLocalSearchParams } from 'expo-router'
import * as Clipboard from 'expo-clipboard'
import { api, ApiError, msg, type ManualJob } from '../src/lib'
import { Btn, Chip, ErrorText, Input, Row, Screen, T } from '../src/ui'

/** Add a job from a link, or paste it in when the site blocks reading (LinkedIn, Naukri). */
export default function AddJob() {
  const params = useLocalSearchParams<{ url?: string }>()
  const [mode, setMode] = useState<'url' | 'manual'>(params.url ? 'manual' : 'url')
  const [url, setUrl] = useState(params.url ?? '')
  const [m, setM] = useState<ManualJob>({ title: '', company: '', location: '', url: params.url ?? '', description: '' })
  const [err, setErr] = useState<ApiError | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit() {
    setBusy(true)
    setErr(null)
    try {
      const j = await (mode === 'url' ? api.importJob(url.trim()) : api.addManualJob(m))
      router.replace(`/job/${j.id}`)
    } catch (x) {
      const e = x instanceof ApiError ? x : new ApiError(0, 'NETWORK', msg(x))
      setErr(e)
      if (mode === 'url' && e.status === 422) { setMode('manual'); setM({ ...m, url: url.trim() }) }
    } finally { setBusy(false) }
  }
  const bind = (k: keyof ManualJob) => ({ value: m[k], onChangeText: (v: string) => setM({ ...m, [k]: v }), error: err?.fields?.[k] })

  return (
    <Screen header>
      <Row>
        <Chip label="From a link" on={mode === 'url'} onPress={() => setMode('url')} />
        <Chip label="Paste it in" on={mode === 'manual'} onPress={() => setMode('manual')} />
      </Row>
      {mode === 'url' ? (
        <>
          <Input label="Job posting link" value={url} onChangeText={setUrl} placeholder="https://jobs.lever.co/…" autoCapitalize="none" keyboardType="url"
            hint="Greenhouse, Lever and most careers pages. LinkedIn and Naukri block this, so paste those in instead." />
          <Btn kind="ghost" small title="Paste from clipboard" onPress={async () => setUrl((await Clipboard.getStringAsync()).trim())} />
        </>
      ) : (
        <>
          {params.url && <T small muted>That site doesn't let JobPilot read the posting. Paste its title, company and description.</T>}
          <Input label="Job title" maxLength={300} {...bind('title')} />
          <Input label="Company" maxLength={200} {...bind('company')} />
          <Input label="Location" maxLength={300} {...bind('location')} />
          <Input label="Link (optional)" maxLength={1000} autoCapitalize="none" keyboardType="url" {...bind('url')} />
          <Input label="Job description" multiline maxLength={50000} {...bind('description')} style={{ minHeight: 200 }} />
        </>
      )}
      {err && !err.fields && <ErrorText>{err.message}</ErrorText>}
      <Btn title={busy ? 'Adding…' : 'Add job'} busy={busy} onPress={submit} />
    </Screen>
  )
}
