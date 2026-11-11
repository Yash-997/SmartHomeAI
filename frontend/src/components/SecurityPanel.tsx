import { useEffect, useRef, useState } from 'react'
import { api, ApiError, type ModeConfig, type SecurityEvent, type SecurityMode, type SecuritySettings } from '../api'
import { notify } from '../ui/toastStore'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const MODES: { id: SecurityMode; label: string; hint: string }[] = [
  { id: 'HOME', label: 'Home', hint: 'Nothing is watched' },
  { id: 'AWAY', label: 'Away', hint: 'Watch for motion and outages' },
  { id: 'NIGHT', label: 'Night', hint: 'Same, with its own settings' },
]
const SEVERITY_TONE = { INFO: 'bg-slate-600', WARNING: 'bg-amber-500/30 text-amber-200', CRITICAL: 'bg-red-500/30 text-red-200' } as const
/** Open alerts are checked often so a new one shows up within seconds of the device reporting motion. */
const OPEN_ALERTS_POLL_MS = 3000
const HISTORY_POLL_MS = 10000

/** The event an alert email links to (?event=<id>#security), highlighted in the lists below. */
function linkedEventId(): string | null {
  try {
    return new URLSearchParams(window.location.search).get('event')
  } catch {
    return null
  }
}

/** Short headline for an event, e.g. "MOTION DETECTED WHILE AWAY". */
function eventTitle(e: Pick<SecurityEvent, 'kind' | 'mode'>): string {
  switch (e.kind) {
    case 'MOTION_ALERT': return `MOTION DETECTED WHILE ${e.mode}`
    case 'DEVICE_OFFLINE': return `DEVICE OFFLINE WHILE ${e.mode}`
    default: return e.kind.replace(/_/g, ' ').toLowerCase()
  }
}

function ConfigEditor({ title, value, onSave }: { title: string; value: ModeConfig; onSave: (c: ModeConfig) => Promise<void> }) {
  const [cfg, setCfg] = useState(value)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dirty = JSON.stringify(cfg) !== JSON.stringify(value)

  const save = async () => {
    setBusy(true)
    setError('')
    try {
      await onSave(cfg)
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fieldErrors)[0] ?? e.message) : 'Could not save')
    } finally {
      setBusy(false)
    }
  }

  return (
    <fieldset className="rounded-lg bg-slate-900 p-3 text-xs">
      <legend className="px-1 text-slate-300">{title}</legend>
      <label className="flex items-center gap-2">
        <input type="checkbox" checked={cfg.motionAlerts} onChange={(e) => setCfg({ ...cfg, motionAlerts: e.target.checked, lightOnMotion: e.target.checked && cfg.lightOnMotion })} />
        Record motion alerts
      </label>
      <p className="mt-2 text-slate-400">One alert per motion episode per device; it stays listed until acknowledged.</p>
      <label className="mt-2 flex items-center gap-2">
        Ignore a new episode within
        <input type="number" min={10} max={3600} value={cfg.cooldownSeconds} aria-label={`${title} alert interval in seconds`}
          onChange={(e) => setCfg({ ...cfg, cooldownSeconds: Number(e.target.value) })}
          className="w-20 rounded border border-slate-600 bg-slate-800 px-1 py-0.5" />
        s of the previous alert
      </label>
      <label className="mt-2 flex items-center gap-2">
        <input type="checkbox" checked={cfg.lightOnMotion} disabled={!cfg.motionAlerts} onChange={(e) => setCfg({ ...cfg, lightOnMotion: e.target.checked })} />
        Also ask the PIR LED to switch on, once per episode (the device's own 10 s timer stays in charge)
      </label>
      {dirty && (
        <button type="button" disabled={busy} onClick={save} className="mt-2 rounded bg-indigo-600 px-3 py-1 hover:bg-indigo-500 disabled:opacity-60">
          {busy ? 'Saving...' : 'Save'}
        </button>
      )}
      {error && <p className="mt-1 text-red-400" role="alert">{error}</p>}
    </fieldset>
  )
}

export default function SecurityPanel() {
  const settings = usePolled(api.securitySettings, 15000)
  const events = usePolled(api.securityEvents, HISTORY_POLL_MS)
  const open = usePolled(api.openSecurityAlerts, OPEN_ALERTS_POLL_MS)
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState(false)
  const seen = useRef<Set<string> | null>(null)
  const [linked] = useState(linkedEventId)
  const scrolled = useRef(false)
  const reloadEvents = events.reload
  const reloadSettings = settings.reload

  const s = settings.data
  const openAlerts = open.data ?? []

  // A toast for each alert that appears while the page is open (not for the ones already there when it loaded).
  useEffect(() => {
    if (!open.data) return
    if (seen.current === null) {
      seen.current = new Set(open.data.map((e) => e.id))
      return
    }
    const fresh = open.data.filter((e) => !seen.current!.has(e.id))
    fresh.forEach((e) => seen.current!.add(e.id))
    if (fresh.length > 0) {
      notify(eventTitle(fresh[0]))
      reloadEvents()
      reloadSettings()
    }
  }, [open.data, reloadEvents, reloadSettings])

  // Opened from an alert email: bring the security panel into view once the events have loaded.
  useEffect(() => {
    if (!linked || scrolled.current || !events.data) return
    scrolled.current = true
    document.getElementById('security')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [linked, events.data])

  const reloadAll = () => {
    open.reload()
    events.reload()
    settings.reload()
  }

  const apply = async (work: () => Promise<SecuritySettings>) => {
    setBusy(true)
    setActionError('')
    try {
      settings.set(await work())
      events.reload()
      open.reload()
      notify('Security settings saved')
    } catch (e) {
      setActionError(e instanceof ApiError ? (Object.values(e.fieldErrors)[0] ?? e.message) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const ack = async (ev: SecurityEvent) => {
    try {
      await api.acknowledgeEvent(ev.id)
      reloadAll()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not acknowledge')
    }
  }
  const ackAll = async () => {
    try {
      await api.acknowledgeAllEvents()
      reloadAll()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not acknowledge')
    }
  }

  return (
    <section id="security" className="scroll-mt-4 rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="security-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="security-heading" className="font-semibold">
          Security {s && s.unacknowledgedAlerts > 0 && <span className="ml-1 rounded bg-red-500/30 px-2 py-0.5 text-xs text-red-200">{s.unacknowledgedAlerts} unacknowledged</span>}
        </h3>
      </div>

      {!s && !settings.error && <PanelSkeleton lines={4} />}
      {settings.error && !s && <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{settings.error} <button onClick={settings.reload} className="underline">Try again</button></p>}

      {s && (
        <div className="mt-3 space-y-4">
          <div className="flex flex-wrap gap-2" role="group" aria-label="Security mode">
            {MODES.map((m) => (
              <button key={m.id} type="button" disabled={busy} aria-pressed={s.mode === m.id} onClick={() => apply(() => api.setSecurityMode(m.id))}
                className={`rounded-lg px-4 py-2 text-left text-sm ${s.mode === m.id ? 'bg-indigo-600' : 'bg-slate-700 hover:bg-slate-600'} disabled:opacity-60`}>
                <span className="block font-medium">{m.label}</span>
                <span className="block text-xs text-slate-300">{m.hint}</span>
              </button>
            ))}
          </div>
          <p className="text-xs text-slate-500">In {s.mode.toLowerCase()} mode since {new Date(s.modeSince).toLocaleString()}. {s.notice}</p>
          {actionError && <p className="text-sm text-red-400" role="alert">{actionError}</p>}

          {openAlerts.length > 0 && (
            <div aria-live="polite">
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-medium text-red-200">Open alerts</h4>
                <button type="button" onClick={ackAll} className="text-xs text-indigo-300 hover:underline">Acknowledge all</button>
              </div>
              <ul className="mt-2 space-y-1">
                {openAlerts.map((e) => (
                  <li key={e.id} role="alert" aria-current={e.id === linked ? 'true' : undefined}
                    className={`flex items-start gap-2 rounded border bg-red-500/10 px-2 py-1.5 text-xs ${e.id === linked ? 'border-amber-300 ring-2 ring-amber-300/60' : 'border-red-500/40'}`}>
                    <span className="flex-1">
                      <span className="block font-semibold tracking-wide text-red-200">{eventTitle(e)}</span>
                      {e.message}
                      <span className="block text-slate-400">{new Date(e.createdAt).toLocaleString()}</span>
                    </span>
                    <button type="button" onClick={() => ack(e)} className="text-indigo-300 hover:underline">Acknowledge</button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <details className="text-sm">
            <summary className="cursor-pointer text-slate-300">What Away and Night do</summary>
            <div className="mt-2 grid gap-3 sm:grid-cols-2">
              <ConfigEditor key={`away-${JSON.stringify(s.away)}`} title="Away" value={s.away} onSave={(c) => apply(() => api.configureSecurity({ away: c }))} />
              <ConfigEditor key={`night-${JSON.stringify(s.night)}`} title="Night" value={s.night} onSave={(c) => apply(() => api.configureSecurity({ night: c }))} />
            </div>
          </details>

          <div>
            <div className="flex items-center justify-between">
              <h4 className="text-sm font-medium">Event history</h4>
            </div>
            {!events.data && !events.error && <PanelSkeleton lines={2} />}
            {events.error && <p className="mt-2 text-sm text-red-400" role="alert">{events.error}</p>}
            {events.data && events.data.length === 0 && <p className="mt-2 rounded-lg bg-slate-900 p-3 text-sm text-slate-400">No security events yet. Switch to Away or Night to start watching.</p>}
            <ul className="mt-2 max-h-72 space-y-1 overflow-y-auto">
              {events.data?.map((e) => (
                <li key={e.id} aria-current={e.id === linked ? 'true' : undefined}
                  className={`flex items-start gap-2 rounded bg-slate-900 px-2 py-1.5 text-xs ${e.id === linked ? 'ring-2 ring-amber-300/60' : ''}`}>
                  <span className={`rounded px-1.5 py-0.5 ${SEVERITY_TONE[e.severity]}`}>{eventTitle(e)}</span>
                  <span className="flex-1">
                    {e.message}
                    <span className="block text-slate-500">{new Date(e.createdAt).toLocaleString()}{e.acknowledgedAt ? ' · acknowledged' : ''}</span>
                  </span>
                  {e.severity !== 'INFO' && !e.acknowledgedAt && <button type="button" onClick={() => ack(e)} className="text-indigo-300 hover:underline">Acknowledge</button>}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </section>
  )
}
