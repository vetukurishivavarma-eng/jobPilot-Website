import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import './index.css'
import { api, token, type User } from './api'
import { SessionCtx, useSession } from './session'
import AuthPage from './pages/AuthPage'
import ProfilePage from './pages/ProfilePage'
import ResumesPage from './pages/ResumesPage'

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
          <Route path="/login" element={user ? <Navigate to="/profile" /> : <AuthPage mode="login" />} />
          <Route path="/register" element={user ? <Navigate to="/profile" /> : <AuthPage mode="register" />} />
          <Route element={user ? <Shell /> : <Navigate to="/login" />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/resumes" element={<ResumesPage />} />
          </Route>
          <Route path="*" element={<Navigate to={user ? '/profile' : '/login'} />} />
        </Routes>
      </BrowserRouter>
    </SessionCtx.Provider>
  )
}

function Shell() {
  const { user, setUser } = useSession()
  const nav = useNavigate()
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-ink text-paper' : 'text-muted hover:text-ink'}`
  return (
    <div className="min-h-screen">
      <header className="border-b border-line bg-paper/90 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-3">
          <span className="font-display text-lg font-bold tracking-tight">
            JobPilot<span className="text-signal">.</span>
          </span>
          <nav className="flex gap-1">
            <NavLink to="/profile" className={link}>Profile</NavLink>
            <NavLink to="/resumes" className={link}>Resumes</NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="hidden text-muted sm:inline">{user?.email}</span>
            <button className="btn-ghost" onClick={() => { token.set(null); setUser(null); nav('/login') }}>Sign out</button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-8"><Outlet /></main>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
