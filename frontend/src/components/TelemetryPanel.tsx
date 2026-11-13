import type { Connection, Device } from '../api'
import { formatAge, liveStatus } from '../telemetry'
import AnimatedNumber from '../ui/AnimatedNumber'
import Sparkline from '../ui/Sparkline'
import { useTelemetryHistory } from './useTelemetryHistory'

const BADGE: Record<Connection, { label: string; dot: string; tone: string; hint: string }> = {
  ONLINE: { label: 'Online', dot: 'bg-green-400', tone: 'bg-green-500/15 text-green-300', hint: 'Sending data right now.' },
  OFFLINE: { label: 'Offline', dot: 'bg-red-400', tone: 'bg-red-500/15 text-red-300', hint: 'Not heard from recently.' },
  NEVER_CONNECTED: { label: 'Never connected', dot: 'bg-slate-400', tone: 'bg-slate-700 text-slate-300', hint: 'Paired, but has not sent any data yet.' },
}

export function ConnectionBadge({ connection }: { connection: Connection }) {
  const b = BADGE[connection]
  return (
    <span className={`flex shrink-0 items-center gap-2 rounded-full px-3 py-1.5 text-xs font-semibold ${b.tone}`} title={b.hint}>
      <span aria-hidden="true" className={`h-2 w-2 rounded-full ${b.dot}`} />
      {b.label}
    </span>
  )
}

function Tile({ label, value, note, missing, dim, num }: { label: string; value: string; note?: string; missing: boolean; dim: boolean; num?: { n: number; decimals: number; suffix: string } }) {
  return (
    <div className={`rounded-xl bg-slate-900/60 p-3.5 transition-opacity duration-200 ${dim ? 'opacity-50' : ''}`}>
      <div className="text-xs font-medium text-slate-400">{label}</div>
      <div key={num && !missing ? 'num' : value} className={`${num && !missing ? '' : 'value-tick '}mt-1 text-xl font-semibold tracking-tight ${missing ? 'text-slate-500' : ''}`}>
        {num && !missing ? <AnimatedNumber value={num.n} decimals={num.decimals} suffix={num.suffix} /> : value}
      </div>
      <div className="mt-0.5 text-[11px] text-slate-500">{missing ? 'no reading' : note}</div>
    </div>
  )
}

/** Temperature and humidity trend lines from the stored history (nothing is drawn until there are at least three readings). */
function Trend({ deviceId }: { deviceId: string }) {
  const history = useTelemetryHistory(deviceId)
  const temps = history.map((h) => h.temperatureC).filter((v): v is number => v !== null)
  const hums = history.map((h) => h.humidityPct).filter((v): v is number => v !== null)
  if (temps.length < 3 && hums.length < 3) return null
  return (
    <div className="mt-3 grid gap-4 sm:grid-cols-2">
      <Sparkline values={temps} label="Temperature" unit="°C" />
      <Sparkline values={hums} label="Humidity" unit="%" />
    </div>
  )
}

export default function TelemetryPanel({ device, elapsedSeconds }: { device: Device; elapsedSeconds: number }) {
  const t = device.latestTelemetry
  const { connection, lastSeenAge, readingAge, stale } = liveStatus(device, elapsedSeconds)

  if (!t) {
    return (
      <p className="mt-3 rounded-md bg-slate-900 p-3 text-sm text-slate-400">
        {connection === 'NEVER_CONNECTED'
          ? 'No data yet. Readings appear here once the device connects and starts reporting.'
          : 'Connected, but no readings received yet.'}
      </p>
    )
  }

  return (
    <div className="mt-3">
      <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
        <span className="text-slate-400">Last reading {formatAge(readingAge ?? t.ageSeconds)} ago</span>
        {stale && (
          <span className="rounded bg-amber-500/20 px-2 py-0.5 font-medium text-amber-300"
            title={`Older than ${device.staleAfterSeconds}s: not current.`}>
            STALE: showing the last known values
          </span>
        )}
        {connection === 'OFFLINE' && lastSeenAge !== null && (
          <span className="text-slate-500">· device last heard {formatAge(lastSeenAge)} ago</span>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Tile label="Temperature" dim={stale} missing={t.temperatureC === null}
          value={t.temperatureC === null ? '—' : `${t.temperatureC.toFixed(1)} °C`}
          num={t.temperatureC === null ? undefined : { n: t.temperatureC, decimals: 1, suffix: ' °C' }} />
        <Tile label="Humidity" dim={stale} missing={t.humidityPct === null}
          value={t.humidityPct === null ? '—' : `${t.humidityPct.toFixed(1)} %`}
          num={t.humidityPct === null ? undefined : { n: t.humidityPct, decimals: 1, suffix: ' %' }} />
        <Tile label="Motion (PIR)" dim={stale} missing={t.motion === null}
          value={t.motion === null ? '—' : t.motion ? 'Detected' : 'None'} />
        <Tile label="Gas (MQ-2 raw)" dim={stale} missing={t.gasRaw === null}
          value={t.gasRaw === null ? '—' : String(t.gasRaw)} note="raw ADC value, not ppm"
          num={t.gasRaw === null ? undefined : { n: t.gasRaw, decimals: 0, suffix: '' }} />
      </div>
      <Trend deviceId={device.id} />
    </div>
  )
}
