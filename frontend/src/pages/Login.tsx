import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api'
import { useAuth } from '../auth'
import Field from '../components/Field'

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
    <main className="flex min-h-screen items-center justify-center bg-slate-900 p-6 text-slate-100">
      <form onSubmit={submit} noValidate className="w-full max-w-sm space-y-4 rounded-xl bg-slate-800 p-6 shadow-lg">
        <h1 className="text-2xl font-bold">Sign in</h1>
        {sessionExpired && (
          <p className="rounded bg-yellow-500/10 p-2 text-sm text-yellow-300">
            Your session has expired. Please sign in again.
          </p>
        )}
        {formError && <p role="alert" className="rounded bg-red-500/10 p-2 text-sm text-red-400">{formError}</p>}
        <Field id="username" label="Username" autoComplete="username" value={username}
          onChange={(e) => setUsername(e.target.value)} error={errors.username} />
        <Field id="password" label="Password" type="password" autoComplete="current-password" value={password}
          onChange={(e) => setPassword(e.target.value)} error={errors.password} />
        <button disabled={busy} className="w-full rounded-md bg-indigo-600 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
        <p className="text-center text-sm text-slate-400">
          No account? <Link to="/register" className="text-indigo-400 hover:underline">Register</Link>
        </p>
      </form>
    </main>
  )
}
