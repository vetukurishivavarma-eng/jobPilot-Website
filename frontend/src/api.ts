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

export type JobStatus = 'NEW' | 'SAVED' | 'DISMISSED'
export type Job = {
  id: string; title: string; company: string; location: string | null; workplace: 'REMOTE' | 'HYBRID' | 'ONSITE' | null
  employmentType: string | null; salaryMin: number | null; salaryMax: number | null; salaryCurrency: string | null
  source: 'greenhouse' | 'lever' | 'workday' | 'adzuna' | 'web' | 'manual'; url: string | null; applyUrl: string | null
  postedAt: string | null; discoveredAt: string; closedAt: string | null; status: JobStatus; origin: 'COMPANY' | 'SEARCH' | 'IMPORT'
  skills: string[]; minYears: number | null; seniority: string | null; educationLevel: string | null
  description: string | null
  match: { score: number | null; good: string[]; gaps: string[] } | null
}
export type JobPage = { items: Job[]; total: number; page: number; size: number }
export type SourceResult = { source: 'company' | 'adzuna'; label: string; fetched: number; linked: number; error: string | null }
export type DiscoveryProgress = { running: boolean; done: number; total: number; results: SourceResult[] }
export type Ats = 'GREENHOUSE' | 'LEVER'
export type ManualJob = { title: string; company: string; location: string; url: string; description: string }

export type FormField = {
  key: string; label: string; type: 'TEXT' | 'TEXTAREA' | 'FILE' | 'SELECT' | 'MULTISELECT'; required: boolean
  options: string[]; value: string | null; source: 'PROFILE' | 'BANK' | 'AI' | 'USER' | 'LETTER' | null
}
export type ApplicationStatus = 'NEEDS_INPUT' | 'READY' | 'FILLING' | 'SUBMITTED' | 'FAILED'
export type Application = {
  id: string; jobId: string; jobTitle: string | null; company: string | null; jobUrl: string | null; applyUrl: string | null
  status: ApplicationStatus; resumeId: string | null; fields: FormField[]; missing: number; error: string | null; hasScreenshot: boolean; auto: boolean
  createdAt: string; updatedAt: string; submittedAt: string | null
}

export type AutoSummary = {
  at: string; considered: number; prepared: number; needYourAnswers: number; submitted: number; waitingForYou: number
  failed: number; notes: string[]
}
export type AutoSettings = {
  enabled: boolean; autoSubmit: boolean; minScore: number; dailyLimit: number
  running: boolean; submittedToday: number; lastRunAt: string | null; lastSummary: AutoSummary | null
}

export type Choice = { original: string; tailored: string | null; useTailored: boolean; why: string | null }
export type TailoredResume = {
  name: string; contact: string; headline: string | null; summary: Choice; skills: Choice
  experiences: { heading: string; meta: string; bullets: Choice }[]; education: string[]; notes: string[]
}
export type CoverLetter = { text: string; source: 'AI' | 'TEMPLATE' | 'USER'; warnings: string[] }
export type TailoredDoc = { id: string; kind: 'RESUME' | 'LETTER'; resume: TailoredResume | null; letter: CoverLetter | null; updatedAt: string }
export type SectionPick = { useTailored: boolean; text?: string }

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
  importProfile: () => request<{ profile: Profile; notes: string[] }>('POST', '/profile/import'),

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

  listJobs: (q: { status: string; q: string; includeClosed: boolean; page: number; sort: string }) =>
    request<JobPage>('GET', '/jobs?' + new URLSearchParams({ ...q, includeClosed: String(q.includeClosed), page: String(q.page), size: '20' })),
  getJob: (id: string) => request<Job>('GET', `/jobs/${id}`),
  setJobStatus: (id: string, status: JobStatus) => request<Job>('PATCH', `/jobs/${id}`, { status }),
  importJob: (url: string) => request<Job>('POST', '/jobs/import', { url }),
  addManualJob: (j: ManualJob) => request<Job>('POST', '/jobs/manual', j),
  runDiscovery: () => request<DiscoveryProgress>('POST', '/discovery/run'),
  discoveryStatus: () => request<DiscoveryProgress>('GET', '/discovery/status'),


  listApplications: () => request<Application[]>('GET', '/applications'),
  getApplication: (id: string) => request<Application>('GET', `/applications/${id}`),
  createApplication: (jobId: string) => request<Application>('POST', '/applications', { jobId }),
  saveAnswers: (id: string, answers: Record<string, string>) => request<Application>('PUT', `/applications/${id}/answers`, { answers }),
  deleteApplication: (id: string) => request<void>('DELETE', `/applications/${id}`),
  jobDocuments: (jobId: string) => request<{ resume: TailoredDoc | null; letter: TailoredDoc | null }>('GET', `/jobs/${jobId}/documents`),
  tailorResume: (jobId: string) => request<TailoredDoc>('POST', `/jobs/${jobId}/documents/resume`),
  writeLetter: (jobId: string) => request<TailoredDoc>('POST', `/jobs/${jobId}/documents/letter`),
  saveResumeChoices: (id: string, c: { summary: SectionPick; skills: SectionPick; experiences: SectionPick[] }) => request<TailoredDoc>('PUT', `/documents/${id}/resume`, c),
  saveLetter: (id: string, text: string) => request<TailoredDoc>('PUT', `/documents/${id}/letter`, { text }),
  downloadDocument: async (id: string, format: 'pdf' | 'docx' | 'txt') => {
    const res = await fetch(`/api/documents/${id}/file?format=${format}`, { headers: { Authorization: `Bearer ${token.get()}` } })
    if (!res.ok) throw new ApiError(res.status, 'DOWNLOAD_FAILED', 'Download failed')
    const name = /filename\*=UTF-8''([^;]+)/.exec(res.headers.get('Content-Disposition') ?? '')?.[1]
    const url = URL.createObjectURL(await res.blob())
    Object.assign(document.createElement('a'), { href: url, download: name ? decodeURIComponent(name) : `document.${format}` }).click()
    URL.revokeObjectURL(url)
  },
  getAuto: () => request<AutoSettings>('GET', '/auto-apply'),
  saveAuto: (s: Pick<AutoSettings, 'enabled' | 'autoSubmit' | 'minScore' | 'dailyLimit'>) => request<AutoSettings>('PUT', '/auto-apply', s),
  runAuto: () => request<AutoSettings>('POST', '/auto-apply/run'),
  /** Authenticated image: fetched as a blob, shown via an object URL. */
  screenshotUrl: async (id: string) => {
    const res = await fetch(`/api/applications/${id}/screenshot`, { headers: { Authorization: `Bearer ${token.get()}` } })
    if (!res.ok) throw new ApiError(res.status, 'NOT_FOUND', 'No screenshot yet')
    return URL.createObjectURL(await res.blob())
  },
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

/**
 * What's still missing for a 100% profile. Jobs and Applications stay locked until this is empty.
 * GitHub and portfolio links are optional; work experience is required only with 1+ years.
 */
export function profileGaps(p: Profile, resumes: Resume[]): { missing: string[]; percent: number } {
  const has = (s: string | null | undefined) => !!s && s.trim() !== ''
  const checks: [string, boolean][] = [
    ['Resume', resumes.length > 0],
    ['Headline', has(p.headline)],
    ['Current title', has(p.currentTitle)],
    ['Phone', has(p.phone)],
    ['Current location', has(p.location)],
    ['Years of experience', p.yearsExperience != null],
    ['Summary', has(p.summary)],
    ['Skills', p.skills.length > 0],
    ['Desired roles', p.desiredRoles.length > 0],
    ['Preferred locations', p.desiredLocations.length > 0],
    ['Work experience', p.experiences.length > 0 || (p.yearsExperience ?? 0) < 1],
    ['Education', p.educations.length > 0],
    ['Expected salary', p.expectedSalary != null && has(p.salaryCurrency)],
    ['Notice period', p.noticePeriodDays != null],
    ['Work authorization', has(p.workAuthorization)],
    ['LinkedIn URL', has(p.linkedinUrl)],
  ]
  const missing = checks.filter(([, ok]) => !ok).map(([name]) => name)
  return { missing, percent: Math.round(100 * (checks.length - missing.length) / checks.length) }
}

/** Pages that change the profile or resumes fire this so the lock re-checks. */
export const profileChanged = () => window.dispatchEvent(new Event('jobpilot:profile'))
