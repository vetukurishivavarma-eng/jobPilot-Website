import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, Navigate, NavLink, Outlet, Route, Routes, useNavigate, useOutletContext } from 'react-router-dom'
import './index.css'
import { api, profileGaps, token, type User } from './api'
import { SessionCtx, useSession } from './session'
import AuthPage from './pages/AuthPage'
import ProfilePage from './pages/ProfilePage'
import ResumesPage from './pages/ResumesPage'
import JobsPage from './pages/JobsPage'
import TailorPage from './pages/TailorPage'
import ApplicationsPage, { ApplicationPage } from './pages/ApplicationsPage'
import InterviewPage, { InterviewSessionPage } from './pages/InterviewPage'
import InsightsPage from './pages/InsightsPage'
import LivePage from './pages/LivePage'

// The phone app opens /live/:id in a WebView with its sign-in token in the URL fragment (never sent to the server).
const handoff = /[#&]t=([^&]+)/.exec(location.hash)
if (handoff) { token.set(decodeURIComponent(handoff[1])); history.replaceState(null, '', location.pathname) }

function App() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(!!token.get())

  useEffect(() => {
    if (!token.get()) return
    api.me().then(setUser).catch(() => token.set(null)).finally(() => setLoading(false))
  }, [])

  if (loading) return <p className="p-8 text-muted">Loading…</p>
  return (
    <SessionCtx.Provider value={{ user, setUser }}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={user ? <Navigate to="/jobs" /> : <AuthPage mode="login" />} />
          <Route path="/register" element={user ? <Navigate to="/jobs" /> : <AuthPage mode="register" />} />
          <Route path="/live/:id" element={user ? <LivePage /> : <Navigate to="/login" />} />
          <Route element={user ? <Shell /> : <Navigate to="/login" />}>
            <Route element={<Unlocked />}>
              <Route path="/jobs" element={<JobsPage />} />
              <Route path="/jobs/:id/tailor" element={<TailorPage />} />
              <Route path="/applications" element={<ApplicationsPage />} />
              <Route path="/applications/:id" element={<ApplicationPage />} />
              <Route path="/interview" element={<InterviewPage />} />
              <Route path="/interview/:id" element={<InterviewSessionPage />} />
              <Route path="/insights" element={<InsightsPage />} />
            </Route>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/resumes" element={<ResumesPage />} />
          </Route>
          <Route path="*" element={<Navigate to={user ? '/jobs' : '/login'} />} />
        </Routes>
      </BrowserRouter>
    </SessionCtx.Provider>
  )
}

type Gaps = { missing: string[]; later: string[]; percent: number } | null

// Jobs and Applications open once the essentials are in (resume, skills, desired roles, preferred locations)
function Unlocked() {
  const gaps = useOutletContext<Gaps>()
  if (!gaps) return <p className="text-muted">Loading…</p>
  return gaps.missing.length ? <Navigate to="/profile" replace /> : <Outlet />
}

function Shell() {
  const { user, setUser } = useSession()
  const nav = useNavigate()
  const [gaps, setGaps] = useState<Gaps>(null)
  useEffect(() => {
    const check = () => Promise.all([api.getProfile(), api.listResumes()]).then(([p, r]) => setGaps(profileGaps(p, r))).catch(() => {})
    check()
    window.addEventListener('jobpilot:profile', check)
    return () => window.removeEventListener('jobpilot:profile', check)
  }, [])
  const locked = !gaps || gaps.missing.length > 0
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-ink text-paper' : 'text-muted hover:text-ink'}`
  const lockedLink = (label: string) => (
    <span className="cursor-not-allowed rounded-md px-3 py-1.5 text-sm font-medium text-muted/50" title="Complete your profile to unlock">🔒 {label}</span>
  )
  return (
    <div className="min-h-screen">
      <header className="border-b border-line bg-paper/90 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-3">
          <span className="font-display text-lg font-bold tracking-tight">
            JobPilot<span className="text-signal">.</span>
          </span>
          <nav className="flex gap-1">
            {locked ? lockedLink('Jobs') : <NavLink to="/jobs" className={link}>Jobs</NavLink>}
            {locked ? lockedLink('Applications') : <NavLink to="/applications" className={link}>Applications</NavLink>}
            {locked ? lockedLink('Interview') : <NavLink to="/interview" className={link}>Interview</NavLink>}
            {locked ? lockedLink('Insights') : <NavLink to="/insights" className={link}>Insights</NavLink>}
            <NavLink to="/profile" className={link}>Profile</NavLink>
            <NavLink to="/resumes" className={link}>Resumes</NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="hidden text-muted sm:inline">{user?.email}</span>
            <button className="btn-ghost" onClick={() => { token.set(null); setUser(null); nav('/login') }}>Sign out</button>
          </div>
        </div>
      </header>
      {gaps && gaps.missing.length > 0 && (
        <div className="border-b border-amber-300 bg-amber-50">
          <div className="mx-auto max-w-5xl px-4 py-3 text-sm">
            <div className="flex flex-wrap items-center gap-3">
              <b>Your profile is {gaps.percent}% complete.</b>
              <span>Jobs and Applications unlock at 100%.</span>
              <div className="h-2 w-32 overflow-hidden rounded-full bg-amber-200" aria-hidden>
                <div className="h-full bg-amber-500" style={{ width: gaps.percent + '%' }} />
              </div>
            </div>
            <p className="mt-1 text-amber-900">
              Still missing: {gaps.missing.join(', ')}.{' '}
              {gaps.missing.includes('Resume') ? <Link className="underline" to="/resumes">Upload a resume</Link> : <Link className="underline" to="/profile">Fill them in on your profile</Link>}
            </p>
          </div>
        </div>
      )}
      <main className="mx-auto max-w-5xl px-4 py-8"><Outlet context={gaps} /></main>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
