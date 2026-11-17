import { Link } from 'react-router-dom'
import { m } from 'motion/react'
import { api, type Level } from '../api'
import { ChevronRightIcon } from '../ui/icons'
import { Skeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

type Tone = 'ok' | 'warn' | 'bad' | 'idle'
const TONE: Record<Tone, { dot: string; text: string; label: string }> = {
  ok: { dot: 'text-green-400', text: 'text-green-300', label: 'Operational' },
  warn: { dot: 'text-amber-400', text: 'text-amber-300', label: 'Degraded' },
  bad: { dot: 'text-red-400', text: 'text-red-300', label: 'Down' },
  idle: { dot: 'text-slate-500', text: 'text-slate-400', label: 'Not set up' },
}
const fromLevel = (l: Level): Tone => (l === 'OK' ? 'ok' : l === 'DEGRADED' ? 'warn' : 'bad')

type Tile = { key: string; label: string; value: string; detail: string; tone: Tone }

/**
 * One row answering "is every part of the system working?". Every value comes from the backend's /api/system/status
 * check; nothing is assumed. When the check cannot be reached, the strip says so instead of showing green.
 */
export default function SystemPulse() {
  const { data, error, reload } = usePolled(api.systemStatus, 30000)

  if (!data && !error) {
    return (
      <div role="status" className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
        <span className="sr-only">Checking the system</span>
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[92px] rounded-xl" />)}
      </div>
    )
  }

  if (!data) {
    return (
      <p role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-200">
        The system status check could not be reached: {error}. <button type="button" onClick={reload} className="underline">Try again</button>
      </p>
    )
  }

  const online = data.devices.filter((d) => d.connection === 'ONLINE').length
  const sensorStates = data.sensors.flatMap((s) => Object.values(s.sensors))
  const fresh = sensorStates.filter((s) => s.state === 'FRESH').length
  const predicting = data.ml.filter((x) => x.predictionsAvailable).length
  const anomalies = data.ml.reduce((n, x) => n + x.activeAnomalies, 0)

  const tiles: Tile[] = [
    { key: 'sys', label: 'System health', value: TONE[fromLevel(data.overall)].label, detail: data.issues.length === 0 ? 'All checks passed' : `${data.issues.length} issue${data.issues.length === 1 ? '' : 's'}`, tone: fromLevel(data.overall) },
    { key: 'db', label: 'Database', value: TONE[fromLevel(data.database.level)].label, detail: data.database.detail, tone: fromLevel(data.database.level) },
    { key: 'mqtt', label: 'MQTT broker', value: TONE[fromLevel(data.mqtt.level)].label, detail: data.mqtt.detail, tone: fromLevel(data.mqtt.level) },
    {
      key: 'dev', label: 'Devices', value: data.devices.length === 0 ? 'None paired' : `${online} / ${data.devices.length} online`,
      detail: data.devices.length === 0 ? 'Pair a device to begin' : online === data.devices.length ? 'All connected' : `${data.devices.length - online} not connected`,
      tone: data.devices.length === 0 ? 'idle' : online === data.devices.length ? 'ok' : online === 0 ? 'bad' : 'warn',
    },
    {
      key: 'sen', label: 'Sensors', value: sensorStates.length === 0 ? 'No data' : `${fresh} / ${sensorStates.length} fresh`,
      detail: sensorStates.length === 0 ? 'Waiting for readings' : fresh === sensorStates.length ? 'All reporting' : `${sensorStates.length - fresh} stale or silent`,
      tone: sensorStates.length === 0 ? 'idle' : fresh === sensorStates.length ? 'ok' : fresh === 0 ? 'bad' : 'warn',
    },
    {
      key: 'ml', label: 'ML & assistant', value: data.assistant.modelConfigured ? 'AI configured' : 'References only',
      detail: `${predicting} of ${data.ml.length} devices forecasting${anomalies ? ` · ${anomalies} anomal${anomalies === 1 ? 'y' : 'ies'}` : ''}`,
      tone: fromLevel(data.assistant.level),
    },
  ]

  return (
    <section aria-labelledby="pulse-heading">
      <div className="mb-3 flex items-baseline justify-between gap-2">
        <h2 id="pulse-heading" className="text-base font-semibold">System status</h2>
        <Link to="/diagnostics" className="inline-flex items-center gap-1 text-sm text-indigo-300 hover:text-indigo-200">
          Diagnostics <ChevronRightIcon width={14} height={14} />
        </Link>
      </div>
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
        {tiles.map((t, i) => (
          <m.li
            key={t.key}
            className="card lift p-4"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.05, duration: 0.35, ease: [0.2, 0.7, 0.2, 1] }}
          >
            <p className="flex items-center gap-2 text-xs text-slate-400">
              {/* The key includes the tone, so the ring breathes again only when the state changes. */}
              <span key={t.tone} aria-hidden="true" className={`status-dot ${TONE[t.tone].dot} ${t.tone !== 'idle' ? 'status-dot-live' : ''}`} />
              {t.label}
            </p>
            <p className={`mt-2 text-[15px] font-semibold ${TONE[t.tone].text}`}>{t.value}</p>
            <p className="mt-1 truncate text-[11px] text-slate-500" title={t.detail}>{t.detail}</p>
          </m.li>
        ))}
      </ul>
      <p className="mt-2 text-[11px] text-slate-500">Checked {new Date(data.checkedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</p>
    </section>
  )
}
