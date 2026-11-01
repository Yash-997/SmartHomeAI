import { Link } from 'react-router-dom'
import { api, type Device } from '../api'
import AnimatedNumber from '../ui/AnimatedNumber'
import Sparkline from '../ui/Sparkline'
import { Skeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'
import { useTelemetryHistory } from './useTelemetryHistory'

function Kpi({ label, children, note, dim }: { label: string; children: React.ReactNode; note: string; dim?: boolean }) {
  return (
    <div className={`rounded-lg bg-slate-900 p-4 transition-opacity duration-200 ${dim ? 'opacity-50' : ''}`}>
      <div className="text-[11px] font-medium uppercase tracking-wider text-slate-500">{label}</div>
      <div className="mt-2 text-2xl font-semibold leading-none">{children}</div>
      <div className="mt-2 text-xs text-slate-500">{note}</div>
    </div>
  )
}

const dash = <span className="text-slate-500">&mdash;</span>

/** The headline numbers of the home, taken from the device that is online (or the first one). */
export default function OverviewStrip() {
  const { data, error } = usePolled(api.listDevices, 10000)
  const primary: Device | null = data ? (data.find((d) => d.connection === 'ONLINE') ?? data[0] ?? null) : null
  const history = useTelemetryHistory(primary?.id ?? null)

  if (!data && !error) {
    return (
      <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-label="Home overview">
        <div role="status" className="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <span className="sr-only">Loading</span>
          {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-24" />)}
        </div>
      </section>
    )
  }
  if (error && !data) {
    return <p className="rounded-xl border border-red-500/40 bg-red-500/10 p-4 text-sm text-red-300" role="alert">{error}</p>
  }
  if (!primary || !data) {
    return (
      <section className="rounded-xl bg-slate-800 p-6 shadow-lg" aria-label="Home overview">
        <h3 className="font-semibold">No devices yet</h3>
        <p className="mt-1 text-sm text-slate-400">Pair your ESP32 on the <Link to="/devices" className="text-indigo-300 underline-offset-2 hover:underline">Devices page</Link> and its readings will appear here.</p>
      </section>
    )
  }

  const t = primary.latestTelemetry
  const online = data.filter((d) => d.connection === 'ONLINE').length
  const stale = t?.stale ?? false
  const temps = history.map((h) => h.temperatureC).filter((v): v is number => v !== null)
  const hums = history.map((h) => h.humidityPct).filter((v): v is number => v !== null)

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="overview-heading">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id="overview-heading" className="font-semibold">Right now</h3>
        <span className="text-xs text-slate-400">
          {primary.name}{stale ? ' · last known values (stale)' : t ? '' : ' · no readings yet'}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Kpi label="Devices online" note={data.length === 1 ? '1 device paired' : `${data.length} devices paired`}>
          <AnimatedNumber value={online} decimals={0} /><span className="text-base font-normal text-slate-400"> / {data.length}</span>
        </Kpi>
        <Kpi label="Temperature" dim={stale} note={t?.temperatureC == null ? 'no reading' : 'degrees Celsius'}>
          {t?.temperatureC == null ? dash : <AnimatedNumber value={t.temperatureC} suffix=" °C" />}
        </Kpi>
        <Kpi label="Humidity" dim={stale} note={t?.humidityPct == null ? 'no reading' : 'relative'}>
          {t?.humidityPct == null ? dash : <AnimatedNumber value={t.humidityPct} suffix=" %" />}
        </Kpi>
        <Kpi label="Motion" dim={stale} note={t?.motion == null ? 'no reading' : 'PIR sensor'}>
          {t?.motion == null ? dash : t.motion ? 'Detected' : 'None'}
        </Kpi>
        <Kpi label="Gas (raw)" dim={stale} note={t?.gasRaw == null ? 'no reading' : 'ADC value, not ppm'}>
          {t?.gasRaw == null ? dash : <AnimatedNumber value={t.gasRaw} decimals={0} />}
        </Kpi>
      </div>
      {(temps.length >= 3 || hums.length >= 3) && (
        <div className="mt-4 grid gap-5 sm:grid-cols-2">
          <div>
            <div className="mb-1 text-[11px] font-medium uppercase tracking-wider text-slate-500">Temperature trend</div>
            <Sparkline values={temps} label="Temperature" unit="°C" />
          </div>
          <div>
            <div className="mb-1 text-[11px] font-medium uppercase tracking-wider text-slate-500">Humidity trend</div>
            <Sparkline values={hums} label="Humidity" unit="%" />
          </div>
        </div>
      )}
    </section>
  )
}
