import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api'
import { useAuth } from '../auth'
import Field from '../components/Field'

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

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-900 p-6 text-slate-100">
      <form onSubmit={submit} noValidate className="w-full max-w-sm space-y-4 rounded-xl bg-slate-800 p-6 shadow-lg">
        <h1 className="text-2xl font-bold">Create account</h1>
        {formError && <p role="alert" className="rounded bg-red-500/10 p-2 text-sm text-red-400">{formError}</p>}
        <Field id="username" label="Username" autoComplete="username" value={username}
          onChange={(e) => setUsername(e.target.value)} error={errors.username} />
        <Field id="email" label="Email" type="email" autoComplete="email" value={email}
          onChange={(e) => setEmail(e.target.value)} error={errors.email} />
        <Field id="password" label="Password" type="password" autoComplete="new-password" value={password}
          onChange={(e) => setPassword(e.target.value)} error={errors.password} />
        <Field id="confirm" label="Confirm password" type="password" autoComplete="new-password" value={confirm}
          onChange={(e) => setConfirm(e.target.value)} error={errors.confirm} />
        <button disabled={busy} className="w-full rounded-md bg-indigo-600 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
          {busy ? 'Creating account...' : 'Register'}
        </button>
        <p className="text-center text-sm text-slate-400">
          Already registered? <Link to="/login" className="text-indigo-400 hover:underline">Sign in</Link>
        </p>
      </form>
    </main>
  )
}
