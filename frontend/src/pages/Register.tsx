import { useState, type FormEvent } from 'react'
import { AnimatePresence, m } from 'motion/react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api'
import { useAuth } from '../auth'
import Field from '../components/Field'
import { AuthCard } from './AuthLayout'

// Mirrors the backend rules; the backend remains the source of truth.
function validate(username: string, email: string, password: string, confirm: string) {
  const v: Record<string, string> = {}
  if (username.trim().length < 3 || username.trim().length > 30) v.username = 'Username must be 3-30 characters'
  else if (!/^[A-Za-z0-9_.-]+$/.test(username.trim())) v.username = "Use only letters, digits, '.', '_' and '-'"
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) v.email = 'Enter a valid email address'
  if (password.length < 8 || password.length > 72) v.password = 'Password must be 8-72 characters'
  if (confirm !== password) v.confirm = 'Passwords do not match'
  return v
}

/** A strength hint only; the backend requires 8-72 characters and nothing more. */
function strengthOf(password: string) {
  if (password.length < 8) return 0
  return [password.length >= 8, password.length >= 12, /[A-Z]/.test(password) && /[a-z]/.test(password), /\d|[^A-Za-z0-9]/.test(password)].filter(Boolean).length
}
const STRENGTH = ['Too short', 'Weak', 'Fair', 'Good', 'Strong']

export default function Register() {
  const { user, register } = useAuth()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState('')
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const v = validate(username, email, password, confirm)
    setErrors(v)
    setFormError('')
    if (Object.keys(v).length) return

    setBusy(true)
    try {
      await register(username.trim(), email.trim(), password)
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

  const strength = strengthOf(password)
  const barColor = strength >= 3 ? 'bg-green-400' : strength === 2 ? 'bg-amber-400' : 'bg-red-400'

  return (
    <AuthCard title="Create your account" subtitle="Set up VÉYRA for your home in a minute.">
      <form onSubmit={submit} noValidate className="space-y-4" aria-busy={busy}>
        <AnimatePresence initial={false}>
          {formError && (
            <m.p key="err" role="alert" className="rounded-lg border border-red-400/30 bg-red-500/10 px-3 py-2.5 text-sm text-red-200"
              initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
              {formError}
            </m.p>
          )}
        </AnimatePresence>
        <Field id="username" label="Username" autoComplete="username" value={username} hint="3-30 characters: letters, digits, '.', '_' or '-'"
          onChange={(e) => setUsername(e.target.value)} error={errors.username} />
        <Field id="email" label="Email" type="email" autoComplete="email" value={email} placeholder="you@example.com"
          onChange={(e) => setEmail(e.target.value)} error={errors.email} />
        <div>
          <Field id="password" label="Password" type="password" autoComplete="new-password" value={password}
            onChange={(e) => setPassword(e.target.value)} error={errors.password} />
          {password && (
            <div className="mt-2 flex items-center gap-3">
              <div className="flex flex-1 gap-1" aria-hidden="true">
                {[0, 1, 2, 3].map((i) => (
                  <span key={i} className={`h-1 flex-1 rounded-full transition-colors duration-300 ${i < strength ? barColor : 'bg-slate-700'}`} />
                ))}
              </div>
              <span className="w-28 text-right text-[11px] text-slate-400">Strength: {STRENGTH[strength]}</span>
            </div>
          )}
        </div>
        <Field id="confirm" label="Confirm password" type="password" autoComplete="new-password" value={confirm}
          onChange={(e) => setConfirm(e.target.value)} error={errors.confirm} />
        <button disabled={busy} className="btn-primary !mt-6 flex w-full items-center justify-center gap-2 rounded-lg py-3 text-sm">
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? 'Creating account...' : 'Create account'}
        </button>
        {busy && <div className="progress-indeterminate" aria-hidden="true" />}
        <p className="text-center text-sm text-slate-400">
          Already registered? <Link to="/login" className="font-semibold text-indigo-300 hover:text-indigo-200 hover:underline">Sign in</Link>
        </p>
      </form>
    </AuthCard>
  )
}
