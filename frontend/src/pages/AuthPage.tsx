import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, token } from '../api'
import { useSession } from '../session'

export default function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { setUser } = useSession()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [error, setError] = useState<ApiError | null>(null)
  const [busy, setBusy] = useState(false)
  const isRegister = mode === 'register'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const res = isRegister ? await api.register(email, password, fullName) : await api.login(email, password)
      token.set(res.token)
      setUser(res.user)
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'NETWORK', 'Cannot reach the server'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-screen md:grid-cols-2">
      <section className="flex flex-col justify-between bg-ink p-8 text-paper md:p-12">
        <span className="font-display text-xl font-bold">JobPilot<span className="text-signal-soft">.</span></span>
        <div className="my-12 md:my-0">
          <h1 className="font-display text-4xl font-bold leading-tight md:text-5xl">Your AI career agent.</h1>
          <p className="mt-4 max-w-sm text-paper/70">Discover, match, apply, get hired. Tell us about yourself once and upload your resume. The agent only ever uses what you've told it.</p>
        </div>
        <ol className="hidden gap-6 text-xs uppercase tracking-widest text-paper/50 md:flex">
          <li>01 Profile</li><li>02 Match</li><li>03 Apply</li><li>04 Track</li>
        </ol>
      </section>

      <section className="flex items-center justify-center p-6">
        <form onSubmit={submit} className="w-full max-w-sm space-y-4">
          <h2 className="font-display text-2xl font-bold">{isRegister ? 'Create your account' : 'Welcome back'}</h2>
          {isRegister && (
            <div>
              <label className="label" htmlFor="name">Full name</label>
              <input id="name" className="field" required maxLength={120} value={fullName} onChange={e => setFullName(e.target.value)} autoComplete="name" />
            </div>
          )}
          <div>
            <label className="label" htmlFor="email">Email</label>
            <input id="email" type="email" className="field" required value={email} onChange={e => setEmail(e.target.value)} autoComplete="email" />
            {error?.fields?.email && <p className="mt-1 text-xs text-danger">{error.fields.email}</p>}
          </div>
          <div>
            <label className="label" htmlFor="password">Password</label>
            <input id="password" type="password" className="field" required minLength={isRegister ? 8 : undefined} maxLength={72}
              value={password} onChange={e => setPassword(e.target.value)} autoComplete={isRegister ? 'new-password' : 'current-password'} />
            {isRegister && <p className="mt-1 text-xs text-muted">At least 8 characters.</p>}
            {error?.fields?.password && <p className="mt-1 text-xs text-danger">{error.fields.password}</p>}
          </div>
          {error && !error.fields && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error.message}</p>}
          <button className="btn w-full" disabled={busy}>{busy ? 'Please wait…' : isRegister ? 'Create account' : 'Sign in'}</button>
          <p className="text-center text-sm text-muted">
            {isRegister ? <>Have an account? <Link className="font-semibold text-ink underline" to="/login">Sign in</Link></>
              : <>New here? <Link className="font-semibold text-ink underline" to="/register">Create an account</Link></>}
          </p>
        </form>
      </section>
    </div>
  )
}
