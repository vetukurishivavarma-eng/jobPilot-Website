// Thin fetch wrapper. Every backend error is {code, message, fields?}.

export type User = { id: string; email: string; fullName: string; createdAt: string }
export type AuthResponse = { token: string; expiresInSeconds: number; user: User }

export type Experience = {
  company: string; title: string; location: string | null
  startDate: string; endDate: string | null; description: string | null
}
export type Education = {
  institution: string; degree: string; field: string | null
  startYear: number | null; endYear: number | null; grade: string | null
}
export type Profile = {
  headline: string | null; phone: string | null; location: string | null; currentTitle: string | null
  yearsExperience: number | null; expectedSalary: number | null; salaryCurrency: string | null
  noticePeriodDays: number | null; workAuthorization: string | null; willingToRelocate: boolean
  linkedinUrl: string | null; githubUrl: string | null; portfolioUrl: string | null; summary: string | null
  skills: string[]; desiredRoles: string[]; desiredLocations: string[]
  experiences: Experience[]; educations: Education[]; updatedAt: string | null
}
export type Resume = {
  id: string; label: string; originalFilename: string; contentType: string; sizeBytes: number
  primary: boolean; textExtracted: boolean; createdAt: string
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>) {
    super(message)
  }
}

const TOKEN_KEY = 'jobpilot.token'
export const token = {
  get: () => { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } },
  set: (t: string | null) => { try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ } },
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const t = token.get()
  if (t) headers.Authorization = `Bearer ${t}`
  let payload: BodyInit | undefined
  if (body instanceof FormData) payload = body
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }

  const res = await fetch(`/api${path}`, { method, headers, body: payload })
  if (res.status === 401 && t) { token.set(null); window.location.assign('/login') }
  if (!res.ok) {
    const e = await res.json().catch(() => ({}))
    throw new ApiError(res.status, e.code ?? 'HTTP_' + res.status, e.message ?? res.statusText, e.fields)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

export const api = {
  register: (email: string, password: string, fullName: string) =>
    request<AuthResponse>('POST', '/auth/register', { email, password, fullName }),
  login: (email: string, password: string) => request<AuthResponse>('POST', '/auth/login', { email, password }),
  me: () => request<User>('GET', '/auth/me'),

  getProfile: () => request<Profile>('GET', '/profile'),
  saveProfile: (p: Profile) => request<Profile>('PUT', '/profile', p),

  listResumes: () => request<Resume[]>('GET', '/resumes'),
  uploadResume: (file: File, label?: string) => {
    const fd = new FormData()
    fd.append('file', file)
    if (label) fd.append('label', label)
    return request<Resume>('POST', '/resumes', fd)
  },
  resumeText: (id: string) => request<{ id: string; extractedText: string }>('GET', `/resumes/${id}`),
  updateResume: (id: string, patch: { label?: string; primary?: boolean }) => request<Resume>('PATCH', `/resumes/${id}`, patch),
  deleteResume: (id: string) => request<void>('DELETE', `/resumes/${id}`),
  /** Authenticated download: fetch as blob, then hand the browser an object URL. */
  downloadResume: async (r: Resume) => {
    const res = await fetch(`/api/resumes/${r.id}/file`, { headers: { Authorization: `Bearer ${token.get()}` } })
    if (!res.ok) throw new ApiError(res.status, 'DOWNLOAD_FAILED', 'Download failed')
    const url = URL.createObjectURL(await res.blob())
    const a = Object.assign(document.createElement('a'), { href: url, download: r.originalFilename })
    a.click()
    URL.revokeObjectURL(url)
  },
}
