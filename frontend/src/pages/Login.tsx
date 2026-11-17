import { useState, type FormEvent } from 'react'
import { AnimatePresence, m } from 'motion/react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api'
import { useAuth } from '../auth'
import Field from '../components/Field'
import { AuthCard } from './AuthLayout'

const userIcon = (
  <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round"><circle cx="12" cy="8" r="4" /><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" /></svg>
)
const lockIcon = (
  <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round"><rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg>
)

export default function Login() {
  const { user, login, sessionExpired } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState('')
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const v: Record<string, string> = {}
    if (!username.trim()) v.username = 'Username is required'
    if (!password) v.password = 'Password is required'
    setErrors(v)
    setFormError('')
    if (Object.keys(v).length) return

    setBusy(true)
    try {
      await login(username.trim(), password)
    } catch (err) {
      if (err instanceof ApiError) {
        setErrors(err.fieldErrors)
        setFormError(Object.keys(err.fieldErrors).length ? '' : err.message)
      } else {
        setFormError('Something went wrong. Please try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthCard title="Welcome back" subtitle="Sign in to your VÉYRA home.">
      <form onSubmit={submit} noValidate className="space-y-5" aria-busy={busy}>
        {sessionExpired && (
          <p className="rounded-lg border border-amber-400/30 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-200">
            Your session has expired. Please sign in again.
          </p>
        )}
        <AnimatePresence initial={false}>
          {formError && (
            <m.p key="err" role="alert" className="overflow-hidden rounded-lg border border-red-400/30 bg-red-500/10 px-3 py-2.5 text-sm text-red-200"
              initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
              {formError}
            </m.p>
          )}
        </AnimatePresence>
        <Field id="username" label="Username" autoComplete="username" value={username} icon={userIcon} placeholder="your.username"
          onChange={(e) => setUsername(e.target.value)} error={errors.username} />
        <Field id="password" label="Password" type="password" autoComplete="current-password" value={password} icon={lockIcon}
          onChange={(e) => setPassword(e.target.value)} error={errors.password} />
        <button disabled={busy} className="btn-primary flex w-full items-center justify-center gap-2 rounded-lg py-3 text-sm">
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
        {busy && <div className="progress-indeterminate" aria-hidden="true" />}
        <p className="text-center text-sm text-slate-400">
          New to VÉYRA? <Link to="/register" className="font-semibold text-indigo-300 hover:text-indigo-200 hover:underline">Create an account</Link>
        </p>
      </form>
    </AuthCard>
  )
}
