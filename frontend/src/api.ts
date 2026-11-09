// Web client. Types, endpoints and profile rules are shared with the mobile app in ../../shared/api.ts.
import { createApi, ApiError, type Resume } from '../../shared/api'
export * from '../../shared/api'

const TOKEN_KEY = 'jobpilot.token'
export const token = {
  get: () => { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } },
  set: (t: string | null) => { try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ } },
}

const shared = createApi({
  baseUrl: () => '',
  getToken: token.get,
  onUnauthorized: () => { token.set(null); window.location.assign('/login') },
})

async function saveAs(res: Response, fallback: string) {
  const name = /filename\*=UTF-8''([^;]+)/.exec(res.headers.get('Content-Disposition') ?? '')?.[1]
  const url = URL.createObjectURL(await res.blob())
  Object.assign(document.createElement('a'), { href: url, download: name ? decodeURIComponent(name) : fallback }).click()
  URL.revokeObjectURL(url)
}

export const api = {
  ...shared,
  uploadResume: (file: File, label?: string) => {
    const fd = new FormData()
    fd.append('file', file)
    if (label) fd.append('label', label)
    return shared.uploadResume(fd)
  },
  downloadDocument: async (id: string, format: 'pdf' | 'docx' | 'txt') =>
    saveAs(await shared.file(`/documents/${id}/file?format=${format}`), `document.${format}`),
  /** Authenticated image: fetched as a blob, shown via an object URL. */
  screenshotUrl: async (id: string) => {
    try { return URL.createObjectURL(await (await shared.file(`/applications/${id}/screenshot`)).blob()) }
    catch (e) { throw e instanceof ApiError ? new ApiError(e.status, 'NOT_FOUND', 'No screenshot yet') : e }
  },
  downloadResume: async (r: Resume) => saveAs(await shared.file(`/resumes/${r.id}/file`), r.originalFilename),
}

/** Pages that change the profile or resumes fire this so the lock re-checks. */
export const profileChanged = () => window.dispatchEvent(new Event('jobpilot:profile'))
