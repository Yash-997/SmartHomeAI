import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth'
import DeviceList from '../components/DeviceList'
import HomeHero from '../components/HomeHero'
import MetricCards from '../components/MetricCards'
import SecurityOverview from '../components/SecurityOverview'
import SystemPulse from '../components/SystemPulse'
import Shell from '../components/Shell'
import { useLiveDevices } from '../components/useLiveDevices'
import { useTelemetryHistory } from '../components/useTelemetryHistory'
import { liveStatus } from '../telemetry'
import { Skeleton } from '../ui/Skeleton'
import TrendChart, { type TrendPoint } from '../ui/TrendChart'

export default function Dashboard() {
  const { user } = useAuth()
  const { devices, error, elapsed, reload } = useLiveDevices()

  // The headline readings come from the device that is online right now (or the first one).
  const primary = devices?.find((d) => liveStatus(d, elapsed).connection === 'ONLINE') ?? devices?.[0] ?? null
  const history = useTelemetryHistory(primary?.id ?? null)
  // Readings without a value stay in the series as null, so the chart leaves a gap there instead of bridging it.
  const temps = useMemo<TrendPoint[]>(() => history.map((h) => ({ at: h.receivedAt, value: h.temperatureC ?? null })), [history])
  const hums = useMemo<TrendPoint[]>(() => history.map((h) => ({ at: h.receivedAt, value: h.humidityPct ?? null })), [history])
  const hasTemps = temps.filter((p) => p.value !== null).length >= 3
  const hasHums = hums.filter((p) => p.value !== null).length >= 3

  if (!user) return null

  return (
    <Shell wide>
      <HomeHero devices={devices} elapsed={elapsed} />
      <SystemPulse />

      {error && !devices && (
        <p className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300" role="alert">
          {error} <button type="button" onClick={reload} className="underline">Try again</button>
        </p>
      )}

      {!devices && !error && (
        <div role="status" className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
          <span className="sr-only">Loading</span>
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-32 rounded-xl" />)}
        </div>
      )}

      {devices && primary && (
        <>
          <MetricCards device={primary} elapsed={elapsed} />
          {devices.length > 1 && <p className="-mt-3 text-xs text-slate-500">Readings from {primary.name}</p>}
        </>
      )}

      {devices && devices.length === 0 && (
        <section className="card p-8 text-center" aria-label="Get started">
          <h2 className="text-lg font-semibold">Connect your first device</h2>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-400">Pair your ESP32 and its temperature, humidity, motion and gas readings will appear here.</p>
          <Link to="/devices" className="btn-primary mt-5 inline-block rounded-lg px-5 py-2.5 text-sm">Add a device</Link>
        </section>
      )}

      <div className="grid items-start gap-6 xl:grid-cols-5">
        <div className="xl:col-span-3">
          {devices && devices.length > 0 && <DeviceList devices={devices} elapsed={elapsed} onChanged={reload} />}
        </div>
        <div className="xl:col-span-2">
          <SecurityOverview />
        </div>
      </div>

      {primary && (hasTemps || hasHums) && (
        <section aria-labelledby="trends-heading" className="space-y-3">
          <div className="flex items-baseline justify-between gap-2">
            <h2 id="trends-heading" className="text-base font-semibold">Trends</h2>
            <p className="text-xs text-slate-500">Recent readings from {primary.name}. Hover or use the arrow keys to inspect a point.</p>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            {hasTemps && (
              <div className="card p-5">
                <div className="mb-4 flex items-baseline justify-between">
                  <p className="text-sm font-medium text-slate-300">Temperature</p>
                  <p className="text-xs text-slate-500">°C</p>
                </div>
                <TrendChart points={temps} label="Temperature" unit="°C" height={150} />
              </div>
            )}
            {hasHums && (
              <div className="card p-5">
                <div className="mb-4 flex items-baseline justify-between">
                  <p className="text-sm font-medium text-slate-300">Humidity</p>
                  <p className="text-xs text-slate-500">%</p>
                </div>
                <TrendChart points={hums} label="Humidity" unit="%" decimals={0} height={150} />
              </div>
            )}
          </div>
        </section>
      )}
    </Shell>
  )
}
