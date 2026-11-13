// Shared by the website (frontend/) and the mobile app (mobile/): every type, endpoint and profile rule lives here,
// so both clients stay in sync. No DOM / React Native imports allowed in this file.
// Every backend error is {code, message, fields?}.


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
  /** yyyy-mm-dd; answers "are you 18+?" on forms. authorizedToWork answers work-authorization (yes) and visa-sponsorship (no) questions. */
  dateOfBirth: string | null; authorizedToWork: boolean
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
  source: 'greenhouse' | 'lever' | 'workday' | 'smartrecruiters' | 'ashby' | 'adzuna' | 'web' | 'manual'; url: string | null; applyUrl: string | null
  postedAt: string | null; discoveredAt: string; closedAt: string | null; status: JobStatus; origin: 'COMPANY' | 'SEARCH' | 'IMPORT'
  skills: string[]; minYears: number | null; seniority: string | null; educationLevel: string | null
  description: string | null
  match: { score: number | null; good: string[]; gaps: string[] } | null
}
export type JobPage = { items: Job[]; total: number; page: number; size: number }
export type SourceResult = { source: 'company' | 'adzuna'; label: string; fetched: number; linked: number; error: string | null }
export type DiscoveryProgress = { running: boolean; done: number; total: number; results: SourceResult[] }
export type Ats = 'GREENHOUSE' | 'LEVER' | 'ASHBY'
export type ManualJob = { title: string; company: string; location: string; url: string; description: string }

export type FormField = {
  key: string; label: string; type: 'TEXT' | 'TEXTAREA' | 'FILE' | 'SELECT' | 'MULTISELECT'; required: boolean
  options: string[]; value: string | null; source: 'PROFILE' | 'BANK' | 'AI' | 'USER' | 'LETTER' | null
}
export type ApplicationStatus = 'NEEDS_INPUT' | 'READY' | 'FILLING' | 'SUBMITTED' | 'FAILED'
export type Stage = 'APPLIED' | 'SCREENING' | 'INTERVIEW' | 'OFFER' | 'REJECTED' | 'ACCEPTED' | 'WITHDRAWN'
export const STAGES: Stage[] = ['APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'ACCEPTED', 'WITHDRAWN']
export const stageIsTerminal = (s: Stage) => s === 'REJECTED' || s === 'ACCEPTED' || s === 'WITHDRAWN'
export type TimelineEntry = { stage: Stage; note: string | null; at: string }
export type Application = {
  id: string; jobId: string; jobTitle: string | null; company: string | null; jobUrl: string | null; applyUrl: string | null
  status: ApplicationStatus; resumeId: string | null; fields: FormField[]; missing: number; error: string | null; hasScreenshot: boolean; auto: boolean
  stage: Stage | null; followUpAt: string | null; timeline: TimelineEntry[]
  createdAt: string; updatedAt: string; submittedAt: string | null
  /** Applied in a city that isn't one of the user's preferred locations: offer adding it. */
  suggestLocation?: string | null
}
export type FollowUpEmail = { subject: string; body: string; source: 'AI' | 'TEMPLATE' }
export type Outreach = { dm: string; subject: string; body: string; source: 'AI' | 'TEMPLATE' }
export type Funnel = {
  total: number; submitted: number; submittedThisWeek: number
  applied: number; screening: number; interview: number; offer: number; rejected: number; accepted: number; withdrawn: number
  replyRatePct: number | null; avgDaysToReply: number | null
}
export type SkillGap = { skill: string; jobs: number }
export type Insights = { funnel: Funnel; jobsConsidered: number; skillGaps: SkillGap[] }

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

export type InterviewFeedback = {
  score: number | null; rubric: Partial<Record<'relevance' | 'specificity' | 'technical' | 'concision', number>>
  improvements: string[]; improvedAnswer: string | null; warnings: string[]
}
/** What the camera saw during one answer, as % of frames (on-device; only these numbers leave the device). */
export type Signals = { seconds?: number; away?: number; eyesOff?: number; tense?: number; smile?: number; pasted?: boolean }
export type InterviewItem = { type: 'BEHAVIOURAL' | 'TECHNICAL' | 'ROLE' | 'INTRO' | 'FOLLOW_UP' | 'PROBE'; question: string; answer: string | null; feedback: InterviewFeedback | null; signals?: Signals | null }
export type InterviewSession = {
  id: string; jobId: string | null; title: string; source: 'AI' | 'TEMPLATE'; mode: 'SET' | 'LIVE'; items: InterviewItem[]; report: InterviewReport | null;
  /** phone seen on camera (1 = warned); ended POLICY = stopped for a phone, QUIT = the candidate ended it; done = all questions answered; recording deleted at recordingExpiresAt */
  violations: number; ended: 'POLICY' | 'QUIT' | 'CHEATING' | null; done: boolean; recordingExpiresAt: string | null; createdAt: string; updatedAt: string
}
export type BillingPlan = { id: 'FIRST' | 'SINGLE' | 'WEEKLY'; label: string; rupees: number; interviews: number; days: number | null }
/** enabled = payments on; credits = live interviews left. Off: live interviews are free. */
export type Billing = { enabled: boolean; credits: number; plans: BillingPlan[] }
export type InterviewSummary = { id: string; jobId: string | null; title: string; mode: 'SET' | 'LIVE'; answered: number; total: number; averageScore: number | null; readiness: number | null; createdAt: string }
/** readiness 0-100; gaps = where the answers fall short of what interviewers expect at this level. */
export type InterviewReport = { readiness: number | null; verdict: string | null; strengths: string[]; gaps: { area: string; detail: string | null }[]; focus: string[]; source: 'AI' | 'TEMPLATE' | 'POLICY' | 'QUIT' | 'CHEATING' }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>) {
    super(message)
  }
}

export type ApiConfig = {
  /** '' on the web (same origin, /api proxied); 'http://192.168.x.x:8080' on the phone. */
  baseUrl: () => string
  getToken: () => string | null
  /** Called on 401 with a token: the client clears it and goes to sign-in. */
  onUnauthorized: () => void
}

export function createApi(cfg: ApiConfig) {
  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = {}
    const t = cfg.getToken()
    if (t) headers.Authorization = `Bearer ${t}`
    let payload: BodyInit | undefined
    if (body instanceof FormData) payload = body
    else if (body instanceof Blob) { payload = body; headers['Content-Type'] = body.type || 'application/octet-stream' }
    else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }

    const res = await fetch(`${cfg.baseUrl()}/api${path}`, { method, headers, body: payload })
    if (res.status === 401 && t) cfg.onUnauthorized()
    if (!res.ok) {
      const e = await res.json().catch(() => ({}))
      throw new ApiError(res.status, e.code ?? 'HTTP_' + res.status, e.message ?? res.statusText, e.fields)
    }
    return res.status === 204 ? (undefined as T) : res.json()
  }

  async function file(path: string) {
    const t = cfg.getToken()
    const res = await fetch(`${cfg.baseUrl()}/api${path}`, { headers: t ? { Authorization: `Bearer ${t}` } : {} })
    if (!res.ok) throw new ApiError(res.status, 'DOWNLOAD_FAILED', 'Download failed')
    return res
  }

  return {
  register: (email: string, password: string, fullName: string) =>
    request<AuthResponse>('POST', '/auth/register', { email, password, fullName }),
  login: (email: string, password: string) => request<AuthResponse>('POST', '/auth/login', { email, password }),
  me: () => request<User>('GET', '/auth/me'),
  /** Emails a 30-minute reset link; same answer whether or not the account exists. */
  forgotPassword: (email: string) => request<{ message: string }>('POST', '/auth/forgot', { email }),

  getProfile: () => request<Profile>('GET', '/profile'),
  saveProfile: (p: Profile) => request<Profile>('PUT', '/profile', p),
  /** Adds a city to the preferred locations (no-op if it's there). */
  addPreferredLocation: async (city: string) => {
    const p = await request<Profile>('GET', '/profile')
    if (p.desiredLocations.some(l => l.trim().toLowerCase() === city.trim().toLowerCase())) return p
    return request<Profile>('PUT', '/profile', { ...p, desiredLocations: [...p.desiredLocations, city] })
  },
  importProfile: () => request<{ profile: Profile; notes: string[] }>('POST', '/profile/import'),

  listResumes: () => request<Resume[]>('GET', '/resumes'),
  /** fd has 'file' (+ optional 'label'); the web passes a File, the phone a {uri, name, type} part. */
  uploadResume: (fd: FormData) => request<Resume>('POST', '/resumes', fd),
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
  markApplied: (id: string) => request<Application>('POST', `/applications/${id}/submitted`),
  /** The filler read these questions on the form page (SmartRecruiters): the server answers them. */
  pageForm: (id: string, fields: Omit<FormField, 'value' | 'source'>[]) => request<Application>('POST', `/applications/${id}/page-form`, { fields }),
  setApplicationStage: (id: string, stage: Stage, note?: string, followUpInDays?: number | null) =>
    request<Application>('PATCH', `/applications/${id}/stage`, { stage, note, followUpInDays }),
  followUpEmail: (id: string) => request<FollowUpEmail>('POST', `/applications/${id}/follow-up-email`),
  outreach: (jobId: string) => request<Outreach>('POST', `/jobs/${jobId}/outreach`),
  insights: () => request<Insights>('GET', '/insights'),
  jobDocuments: (jobId: string) => request<{ resume: TailoredDoc | null; letter: TailoredDoc | null }>('GET', `/jobs/${jobId}/documents`),
  tailorResume: (jobId: string) => request<TailoredDoc>('POST', `/jobs/${jobId}/documents/resume`),
  writeLetter: (jobId: string) => request<TailoredDoc>('POST', `/jobs/${jobId}/documents/letter`),
  saveResumeChoices: (id: string, c: { summary: SectionPick; skills: SectionPick; experiences: SectionPick[] }) => request<TailoredDoc>('PUT', `/documents/${id}/resume`, c),
  saveLetter: (id: string, text: string) => request<TailoredDoc>('PUT', `/documents/${id}/letter`, { text }),
  getAuto: () => request<AutoSettings>('GET', '/auto-apply'),
  saveAuto: (s: Pick<AutoSettings, 'enabled' | 'autoSubmit' | 'minScore' | 'dailyLimit'>) => request<AutoSettings>('PUT', '/auto-apply', s),
  runAuto: () => request<AutoSettings>('POST', '/auto-apply/run'),
  listInterviews: () => request<InterviewSummary[]>('GET', '/interview/sessions'),
  getInterview: (id: string) => request<InterviewSession>('GET', `/interview/sessions/${id}`),
  /** Pass a jobId from the user's job list, or a role to practise for. */
  startInterview: (s: ({ jobId: string } | { role: string }) & { live?: boolean }) => request<InterviewSession>('POST', '/interview/sessions', s),
  answerInterviewAudio: (id: string, index: number, fd: FormData) => request<InterviewSession>('POST', `/interview/sessions/${id}/answers/${index}/audio`, fd),
  interviewViolation: (id: string, kind: 'PHONE' | 'AWAY') => request<InterviewSession>('POST', `/interview/sessions/${id}/violations`, { kind }),
  endInterview: (id: string) => request<InterviewSession>('POST', `/interview/sessions/${id}/end`),
  interviewReport: (id: string) => request<InterviewSession>('POST', `/interview/sessions/${id}/report`),
  uploadRecording: (id: string, video: Blob) => request<InterviewSession>('PUT', `/interview/sessions/${id}/recording`, video),
  recordingFile: (id: string) => file(`/interview/sessions/${id}/recording`),
  billing: () => request<Billing>('GET', '/billing'),
  buy: (plan: BillingPlan['id']) => request<{ purchaseId: string; payUrl: string }>('POST', '/billing/orders', { plan }),
  answerInterview: (id: string, index: number, answer: string, signals?: Signals) =>
    request<InterviewSession>('POST', `/interview/sessions/${id}/answers/${index}`, { answer, signals }),
  deleteInterview: (id: string) => request<void>('DELETE', `/interview/sessions/${id}`),
    /** Authenticated GET for files (PDF, DOCX, screenshot); each client turns the Response into a download or image. */
    file,
    documentFile: (id: string, format: 'pdf' | 'docx' | 'txt') => file(`/documents/${id}/file?format=${format}`),
  }
}

/**
 * What's still missing for a 100% profile. Jobs and Applications stay locked until this is empty.
 * GitHub and portfolio links are optional; work experience is required only with 1+ years.
 */
/** missing = what blocks jobs (matching needs only these); later = asked when an application form needs it. */
/** Sites whose application forms the agent fills. Everything else: the user applies on the company site. */
export const AGENT_SOURCES: Job['source'][] = ['greenhouse', 'lever', 'ashby', 'smartrecruiters', 'workday']

/** Can the agent apply to this job? If not, a one-line reason for the user. */
export function agentSupport(j: Pick<Job, 'source' | 'closedAt'>): { can: boolean; why: string | null } {
  if (j.closedAt) return { can: false, why: 'This job is closed.' }
  if (AGENT_SOURCES.includes(j.source)) return { can: true, why: null }
  const why: Partial<Record<Job['source'], string>> = {
    adzuna: 'Found on a job board: it links to the company\'s own application.',
  }
  return { can: false, why: why[j.source] ?? 'The agent fills Greenhouse, Lever, Ashby, SmartRecruiters and Workday forms. Apply on the company site.' }
}

export function profileGaps(p: Profile, resumes: Resume[]): { missing: string[]; later: string[]; percent: number } {
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
  const gaps = checks.filter(([, ok]) => !ok).map(([name]) => name)
  const blocking = ['Resume', 'Skills', 'Desired roles', 'Preferred locations']
  return {
    missing: gaps.filter(g => blocking.includes(g)),
    later: gaps.filter(g => !blocking.includes(g)),
    percent: Math.round(100 * (checks.length - gaps.length) / checks.length),
  }
}


/**
 * Best bets: jobs you've done a live mock interview for, ranked by fit = average of the job match score and your
 * interview readiness for that job. Latest finished interview per job counts. Not a promise of an offer.
 */
export type BestBet = { job: Job; readiness: number; fit: number }
export async function bestBets(api: Pick<ReturnType<typeof createApi>, 'listInterviews' | 'getJob'>, limit = 5): Promise<BestBet[]> {
  const latest = new Map<string, number>()
  for (const s of await api.listInterviews()) // newest first
    if (s.mode === 'LIVE' && s.jobId && s.readiness != null && !latest.has(s.jobId)) latest.set(s.jobId, s.readiness)
  const bets = await Promise.all([...latest].map(([id, readiness]) => api.getJob(id).then(job => {
    if (job.closedAt || job.status === 'DISMISSED') return null
    return { job, readiness, fit: Math.round(((job.match?.score ?? readiness) + readiness) / 2) }
  }).catch(() => null)))
  return bets.filter((b): b is BestBet => !!b).sort((a, b) => b.fit - a.fit).slice(0, limit)
}
