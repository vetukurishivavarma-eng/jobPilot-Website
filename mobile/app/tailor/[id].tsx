import { useEffect, useState } from 'react'
import { View } from 'react-native'
import { Stack, useLocalSearchParams } from 'expo-router'
import { api, msg, sharePdf, type Choice, type Job, type TailoredDoc } from '../../src/lib'
import { Btn, Card, Chip, ErrorText, Footer, Label, Loading, Row, Screen, T, useColors } from '../../src/ui'

/** Per-job tailored resume and cover letter: read, regenerate, share as PDF. Picking sections stays on the website. */
export default function Tailor() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const [job, setJob] = useState<Job | null>(null)
  const [docs, setDocs] = useState<{ resume: TailoredDoc | null; letter: TailoredDoc | null } | null>(null)
  const [tab, setTab] = useState<'resume' | 'letter'>('resume')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'gen' | 'pdf' | null>(null)

  useEffect(() => {
    api.getJob(id).then(setJob).catch(e => setError(msg(e)))
    api.jobDocuments(id).then(setDocs).catch(e => setError(msg(e)))
  }, [id])
  if (!job || !docs) return error ? <Screen header><ErrorText>{error}</ErrorText></Screen> : <Loading />

  const doc = docs[tab]
  async function generate() {
    setBusy('gen')
    setError(null)
    try {
      const d = tab === 'resume' ? await api.tailorResume(id) : await api.writeLetter(id)
      setDocs({ ...docs!, [tab]: d })
    } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }
  async function pdf() {
    setBusy('pdf')
    setError(null)
    try { await sharePdf(doc!.id, `${tab === 'resume' ? 'Resume' : 'Cover letter'} - ${job!.company}.pdf`) } catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }

  return (
    <Screen header footer={doc ? (
      <Footer>
        <View style={{ flex: 1 }}><Btn kind="ghost" title={tab === 'resume' ? 'Start over' : 'Rewrite'} busy={busy === 'gen'} disabled={!!busy} onPress={generate} /></View>
        <View style={{ flex: 1 }}><Btn title="Share PDF" busy={busy === 'pdf'} disabled={!!busy} onPress={pdf} /></View>
      </Footer>
    ) : undefined}>
      <Stack.Screen options={{ title: `Tailor for ${job.company}` }} />
      <T muted small>{job.title}. Everything comes from your profile and resume; nothing is made up.</T>
      <Row>
        <Chip label="Resume" on={tab === 'resume'} onPress={() => setTab('resume')} />
        <Chip label="Cover letter" on={tab === 'letter'} onPress={() => setTab('letter')} />
      </Row>
      <ErrorText>{error}</ErrorText>

      {!doc ? (
        <Card>
          <T>{tab === 'resume'
            ? 'Skills this job wants go first, your most relevant experience leads, and AI rewording is fact-checked against your profile.'
            : 'Written from your profile. Any claim the fact check can’t trace to your facts is flagged.'}</T>
          <Btn kind="accent" title={busy ? 'Working… (local AI can take a minute)' : tab === 'resume' ? 'Tailor my resume' : 'Write a cover letter'} busy={!!busy} onPress={generate} />
        </Card>
      ) : doc.resume ? <ResumeView doc={doc} /> : doc.letter ? (
        <>
          <T small muted>{doc.letter.source === 'AI' ? 'AI draft from your profile. Read it before sending.' : doc.letter.source === 'TEMPLATE' ? 'Built from your profile (local AI wasn’t available).' : 'Your edited version.'}</T>
          {doc.letter.warnings.length > 0 && <Card tone="warn">{doc.letter.warnings.map((w, i) => <T key={i} small>• {w}</T>)}</Card>}
          <Card><T selectable>{doc.letter.text}</T></Card>
        </>
      ) : null}
    </Screen>
  )
}

function ResumeView({ doc }: { doc: TailoredDoc }) {
  const c = useColors()
  const r = doc.resume!
  const sections: [string, Choice][] = [['Summary', r.summary], ['Skills', r.skills], ...r.experiences.map((e): [string, Choice] => [e.heading, e.bullets])]
  return (
    <>
      {r.notes.length > 0 && <Card tone="warn"><Label>Fact check</Label>{r.notes.map((n, i) => <T key={i} small>• {n} Your original was kept.</T>)}</Card>}
      {sections.map(([title, ch]) => {
        const tailored = ch.useTailored && ch.tailored != null
        return (
          <Card key={title}>
            <Row>
              <T bold style={{ flex: 1 }}>{title}</T>
              <T small style={{ color: tailored ? c.accent : c.muted }}>{tailored ? 'Tailored' : 'Original'}</T>
            </Row>
            {tailored && ch.why && <T small muted>{ch.why}</T>}
            <T selectable small>{(tailored ? ch.tailored : ch.original) || 'Empty'}</T>
          </Card>
        )
      })}
    </>
  )
}
