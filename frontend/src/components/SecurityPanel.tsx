import { useEffect, useRef, useState } from 'react'
import { api, ApiError, type ModeConfig, type SecurityEvent, type SecurityMode, type SecuritySettings } from '../api'
import { notify } from '../ui/toastStore'
import { eventTitle, relativeTime } from '../securityEvents'
import { AlertIcon, CheckIcon, MoonIcon, ShieldIcon, SunHomeIcon } from '../ui/icons'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const MODES: { id: SecurityMode; label: string; hint: string; chip: string; Icon: typeof ShieldIcon }[] = [
  { id: 'HOME', label: 'Home', hint: 'Nothing is watched', chip: 'bg-green-500/15 text-green-300', Icon: SunHomeIcon },
  { id: 'AWAY', label: 'Away', hint: 'Watch for motion and outages', chip: 'bg-indigo-400/15 text-indigo-300', Icon: ShieldIcon },
  { id: 'NIGHT', label: 'Night', hint: 'Same, with its own settings', chip: 'bg-sky-400/15 text-sky-300', Icon: MoonIcon },
]
const SEVERITY_DOT = { INFO: 'bg-slate-500', WARNING: 'bg-amber-400', CRITICAL: 'bg-red-400' } as const
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
    <fieldset className="rounded-xl border border-slate-700 bg-slate-900 p-4 text-sm">
      <legend className="px-1 font-medium text-slate-100">{title}</legend>
      <label className="flex items-center gap-2">
        <input type="checkbox" className="accent-indigo-400" checked={cfg.motionAlerts} onChange={(e) => setCfg({ ...cfg, motionAlerts: e.target.checked, lightOnMotion: e.target.checked && cfg.lightOnMotion })} />
        Record motion alerts
      </label>
      <p className="mt-2 text-xs text-slate-400">One alert per motion episode per device; it stays listed until acknowledged.</p>
      <label className="mt-2 flex items-center gap-2">
        Ignore a new episode within
        <input type="number" min={10} max={3600} value={cfg.cooldownSeconds} aria-label={`${title} alert interval in seconds`}
          onChange={(e) => setCfg({ ...cfg, cooldownSeconds: Number(e.target.value) })}
          className="w-20 rounded-md border border-slate-600 bg-slate-800 px-2 py-1" />
        s of the previous alert
      </label>
      <label className="mt-2 flex items-center gap-2">
        <input type="checkbox" className="accent-indigo-400" checked={cfg.lightOnMotion} disabled={!cfg.motionAlerts} onChange={(e) => setCfg({ ...cfg, lightOnMotion: e.target.checked })} />
        Also ask the PIR LED to switch on, once per episode (the device's own 10 s timer stays in charge)
      </label>
      {dirty && (
        <button type="button" disabled={busy} onClick={save} className="btn-primary mt-3 rounded-lg px-4 py-1.5 text-sm">
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

  const title = (e: SecurityEvent) => eventTitle(e)
  return (
    <div id="security" className="scroll-mt-4 space-y-6">
      {!s && !settings.error && <section className="card p-5"><PanelSkeleton lines={4} /></section>}
      {settings.error && !s && <p className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300" role="alert">{settings.error} <button onClick={settings.reload} className="underline">Try again</button></p>}

      {s && (
        <>
          <section className="card p-5" aria-labelledby="security-mode-heading">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 id="security-mode-heading" className="font-semibold">Mode</h2>
              <p className="text-xs text-slate-400">In {s.mode.toLowerCase()} mode since {new Date(s.modeSince).toLocaleString()}</p>
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-3" role="group" aria-label="Security mode">
              {MODES.map((m) => {
                const on = s.mode === m.id
                return (
                  <button key={m.id} type="button" disabled={busy} aria-pressed={on} onClick={() => apply(() => api.setSecurityMode(m.id))}
                    className={`flex items-center gap-3 rounded-2xl p-4 text-left transition disabled:opacity-60 ${on ? 'bg-slate-700 ring-2 ring-indigo-400' : 'bg-slate-900/60 hover:bg-slate-700/70'}`}>
                    <span aria-hidden="true" className={`grid h-11 w-11 shrink-0 place-items-center rounded-xl ${m.chip}`}><m.Icon /></span>
                    <span className="min-w-0 flex-1">
                      <span className="block font-semibold">{m.label}</span>
                      <span className="block text-xs text-slate-400">{m.hint}</span>
                    </span>
                    {on && <CheckIcon aria-hidden="true" width={20} height={20} className="shrink-0 text-indigo-400" />}
                  </button>
                )
              })}
            </div>
            {s.notice && (
              <details className="mt-3 text-xs text-slate-400">
                <summary className="cursor-pointer hover:text-slate-200">How modes work</summary>
                <p className="mt-1.5 max-w-prose">{s.notice}</p>
              </details>
            )}
            {actionError && <p className="mt-3 text-sm text-red-300" role="alert">{actionError}</p>}
          </section>

          <section className={`card p-5 ${openAlerts.length > 0 ? 'border-red-400/40' : ''}`} aria-labelledby="open-alerts-heading" aria-live="polite">
            <div className="flex items-center justify-between gap-2">
              <h2 id="open-alerts-heading" className="font-semibold">
                Open alerts {openAlerts.length > 0 && <span className="ml-1 rounded-full bg-red-400/20 px-2 py-0.5 text-xs font-medium text-red-200">{openAlerts.length}</span>}
              </h2>
              {openAlerts.length > 1 && <button type="button" onClick={ackAll} className="btn-secondary rounded-lg px-3 py-1.5 text-xs">Acknowledge all</button>}
            </div>
            {openAlerts.length === 0 ? (
              <p className="mt-3 flex items-center gap-2 text-sm text-slate-400"><CheckIcon width={16} height={16} className="text-green-400" />Nothing needs your attention.</p>
            ) : (
              <ul className="mt-3 space-y-2">
                {openAlerts.map((e) => (
                  <li key={e.id} role="alert" aria-current={e.id === linked ? 'true' : undefined}
                    className={`flex items-start gap-3 rounded-xl border bg-red-500/10 px-4 py-3.5 text-sm ${e.id === linked ? 'border-amber-300 ring-2 ring-amber-300/60' : 'border-red-400/30'}`}>
                    <AlertIcon aria-hidden="true" width={20} height={20} className="mt-0.5 shrink-0 text-red-300" />
                    <span className="flex-1">
                      <span className="block font-medium text-red-200">{title(e)}</span>
                      <span className="block text-slate-300">{e.message}</span>
                      <span className="block text-xs text-slate-500">{new Date(e.createdAt).toLocaleString()}</span>
                    </span>
                    <button type="button" onClick={() => ack(e)} className="btn-secondary shrink-0 rounded-lg px-3 py-1.5 text-xs">Acknowledge</button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card p-5" aria-labelledby="security-history-heading">
            <h2 id="security-history-heading" className="font-semibold">History</h2>
            {!events.data && !events.error && <PanelSkeleton lines={3} />}
            {events.error && <p className="mt-3 text-sm text-red-300" role="alert">{events.error}</p>}
            {events.data && events.data.length === 0 && <p className="mt-3 rounded-lg bg-slate-900 p-4 text-sm text-slate-400">No security events yet. Switch to Away or Night to start watching.</p>}
            <ol className="relative mt-4 max-h-[28rem] space-y-1 overflow-y-auto before:absolute before:bottom-2 before:left-[5px] before:top-2 before:w-px before:bg-slate-700">
              {events.data?.map((e) => (
                <li key={e.id} aria-current={e.id === linked ? 'true' : undefined}
                  className={`relative flex items-start gap-4 rounded-xl py-2.5 pl-6 pr-2 text-sm ${e.id === linked ? 'ring-2 ring-amber-300/60' : ''}`}>
                  <span aria-hidden="true" className={`absolute left-0 top-4 h-[11px] w-[11px] rounded-full ring-4 ring-slate-800 ${SEVERITY_DOT[e.severity]}`} />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-baseline gap-x-2">
                      <span className="font-medium">{title(e)}</span>
                      <span className="text-xs text-slate-500" title={new Date(e.createdAt).toLocaleString()}>{relativeTime(e.createdAt)}{e.acknowledgedAt ? ' · acknowledged' : ''}</span>
                    </span>
                    <span className="block text-slate-400">{e.message}</span>
                  </span>
                  {e.severity !== 'INFO' && !e.acknowledgedAt && <button type="button" onClick={() => ack(e)} className="btn-secondary shrink-0 rounded-lg px-3 py-1.5 text-xs">Acknowledge</button>}
                </li>
              ))}
            </ol>
          </section>

          <details className="card group p-5">
            <summary className="cursor-pointer list-none font-semibold marker:hidden">
              Away and Night settings <span className="ml-1 text-xs font-normal text-slate-400">(what each mode does)</span>
            </summary>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <ConfigEditor key={`away-${JSON.stringify(s.away)}`} title="Away" value={s.away} onSave={(c) => apply(() => api.configureSecurity({ away: c }))} />
              <ConfigEditor key={`night-${JSON.stringify(s.night)}`} title="Night" value={s.night} onSave={(c) => apply(() => api.configureSecurity({ night: c }))} />
            </div>
          </details>
        </>
      )}
    </div>
  )
}
