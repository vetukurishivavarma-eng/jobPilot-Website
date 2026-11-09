// Phone client. Every type, endpoint and profile rule comes from ../../shared/api.ts (shared with the website).
import { createContext, useContext } from 'react'
import * as SecureStore from 'expo-secure-store'
import { File, Paths } from 'expo-file-system'
import * as Sharing from 'expo-sharing'
import { createApi, ApiError, type ApplicationStatus, type User } from '../../shared/api'
export * from '../../shared/api'

let server = ''
let token: string | null = null
export const auth = { onSignedOut: () => {} }

export const api = createApi({
  baseUrl: () => server,
  getToken: () => token,
  onUnauthorized: () => { setToken(null); auth.onSignedOut() },
})

export async function loadStored() {
  server = (await SecureStore.getItemAsync('server')) ?? ''
  token = await SecureStore.getItemAsync('token')
  return { server, token }
}
export async function setServer(s: string) {
  server = s.trim().replace(/\/+$/, '')
  if (!/^https?:\/\//.test(server)) server = 'http://' + server
  await SecureStore.setItemAsync('server', server)
}
export function setToken(t: string | null) {
  token = t
  return t ? SecureStore.setItemAsync('token', t) : SecureStore.deleteItemAsync('token')
}
export const serverAddress = () => server

export const msg = (e: unknown) =>
  e instanceof ApiError ? e.message : `Can't reach ${server || 'the server'}. Is the PC on, JobPilot running, and the phone on the same Wi-Fi?`

export type Gaps = { missing: string[]; percent: number }
type Session = { user: User | null; setUser: (u: User | null) => void; gaps: Gaps | null; recheck: () => Promise<void> }
export const SessionCtx = createContext<Session>({ user: null, setUser: () => {}, gaps: null, recheck: async () => {} })
export const useSession = () => useContext(SessionCtx)

/** Downloads a tailored resume / cover letter as PDF and opens the share sheet (save, WhatsApp, mail...). */
export async function sharePdf(docId: string, fallbackName: string) {
  const res = await api.documentFile(docId, 'pdf')
  const header = /filename\*=UTF-8''([^;]+)/.exec(res.headers.get('Content-Disposition') ?? '')?.[1]
  const name = (header ? decodeURIComponent(header) : fallbackName).replace(/[\\/:*?"<>|]/g, '_')
  const f = new File(Paths.cache, name)
  if (f.exists) f.delete()
  f.create()
  f.write(new Uint8Array(await res.arrayBuffer()))
  await Sharing.shareAsync(f.uri, { mimeType: 'application/pdf', dialogTitle: name })
}

export function ago(iso: string) {
  const d = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  return d <= 0 ? 'today' : d === 1 ? 'yesterday' : d < 30 ? `${d}d ago` : new Date(iso).toLocaleDateString()
}

/** Is this clipboard text a link worth offering as a job? */
export function looksLikeJob(s: string) {
  const t = s.trim()
  if (!/^https?:\/\/\S+$/i.test(t)) return false
  return /greenhouse|lever\.co|myworkdayjobs|workday|smartrecruiters|ashbyhq|workable|linkedin\.com\/jobs|naukri|indeed|instahyre|wellfound|careers?|jobs?|position|opening|vacanc|apply/i.test(t)
}

/** Label + tag tone per status, in the order the Applications list shows them. */
export const STATUS: Record<ApplicationStatus, [string, 'warn' | 'accent' | 'ink' | 'danger' | undefined]> = {
  NEEDS_INPUT: ['Needs your answers', 'warn'],
  FAILED: ['Needs attention', 'danger'],
  READY: ['Ready to fill', 'accent'],
  FILLING: ['Filling…', undefined],
  SUBMITTED: ['Submitted', 'ink'],
}

