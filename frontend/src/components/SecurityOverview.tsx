import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type SecurityMode } from '../api'
import { eventTitle, relativeTime } from '../securityEvents'
import { CheckIcon, ChevronRightIcon } from '../ui/icons'
import { PanelSkeleton } from '../ui/Skeleton'
import { notify } from '../ui/toastStore'
import { usePolled } from './usePolled'

const MODES: { id: SecurityMode; label: string }[] = [
  { id: 'HOME', label: 'Home' },
  { id: 'AWAY', label: 'Away' },
  { id: 'NIGHT', label: 'Night' },
]

/** The current security mode with a one-tap switch, and only the alerts that still need attention. */
export default function SecurityOverview() {
  const settings = usePolled(api.securitySettings, 15000)
  const open = usePolled(api.openSecurityAlerts, 5000)
  const [busy, setBusy] = useState(false)
  const s = settings.data
  const alerts = open.data ?? []

  const choose = async (mode: SecurityMode) => {
    if (s?.mode === mode) return
    setBusy(true)
    try {
      settings.set(await api.setSecurityMode(mode))
      open.reload()
      notify(`Security set to ${mode.toLowerCase()}`)
    } catch (e) {
      notify(e instanceof ApiError ? (Object.values(e.fieldErrors)[0] ?? e.message) : 'Could not change the mode', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card p-5" aria-labelledby="security-overview-heading">
      <div className="flex items-center justify-between gap-3">
        <h2 id="security-overview-heading" className="font-semibold">Security</h2>
        <Link to="/security" className="inline-flex items-center gap-1 text-sm text-indigo-400 hover:text-indigo-300">Manage <ChevronRightIcon width={14} height={14} /></Link>
      </div>

      {!s && !settings.error && <PanelSkeleton lines={2} />}
      {settings.error && !s && (
        <p className="mt-3 text-sm text-red-300" role="alert">{settings.error} <button type="button" onClick={settings.reload} className="underline">Try again</button></p>
      )}

      {s && (
        <>
          <div role="group" aria-label="Security mode" className="mt-4 grid grid-cols-3 gap-1 rounded-xl border border-slate-700 bg-slate-900 p-1">
            {MODES.map((m) => (
              <button key={m.id} type="button" disabled={busy} aria-pressed={s.mode === m.id} onClick={() => choose(m.id)}
                className={`rounded-lg py-2 text-sm font-medium disabled:opacity-60 ${s.mode === m.id ? 'bg-indigo-400 text-slate-950' : 'text-slate-300 hover:bg-slate-700'}`}>
                {m.label}
              </button>
            ))}
          </div>

          <div className="mt-4" aria-live="polite">
            {alerts.length === 0 ? (
              <p className="flex items-center gap-2 text-sm text-slate-400"><CheckIcon width={16} height={16} className="text-green-400" />No alerts need your attention</p>
            ) : (
              <ul className="space-y-2">
                {alerts.slice(0, 3).map((e) => (
                  <li key={e.id} className="rounded-lg border border-red-400/30 bg-red-500/10 px-3 py-2">
                    <p className="text-sm font-medium text-red-200">{eventTitle(e)}</p>
                    <p className="mt-0.5 text-xs text-slate-400">{relativeTime(e.createdAt)}</p>
                  </li>
                ))}
                {alerts.length > 3 && <li className="text-xs text-slate-400">and {alerts.length - 3} more in <Link to="/security" className="text-indigo-400 hover:underline">Security</Link></li>}
              </ul>
            )}
          </div>
        </>
      )}
    </section>
  )
}
