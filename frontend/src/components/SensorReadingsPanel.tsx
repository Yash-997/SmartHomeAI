import { useEffect, useState } from 'react'
import { api, type Device } from '../api'
import { gasView, motionView, type Tone } from '../sensorStatus'
import { formatAge, liveStatus } from '../telemetry'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const TONE: Record<Tone, string> = {
  ok: 'text-emerald-300',
  warn: 'text-amber-300',
  alarm: 'font-medium text-red-300',
  muted: 'text-slate-500',
}

function Row({ d, elapsed }: { d: Device; elapsed: number }) {
  const { connection, readingAge, stale } = liveStatus(d, elapsed)
  const gas = gasView(d, stale)
  const motion = motionView(d, stale)
  return (
    <li className={`rounded-lg bg-slate-900 p-3 text-sm ${gas.tone === 'alarm' ? 'ring-1 ring-red-500/50' : ''}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium">{d.name}</span>
        <span className="text-xs text-slate-500">
          {connection.replace('_', ' ').toLowerCase()} · {readingAge === null ? 'no readings yet' : `updated ${formatAge(readingAge)} ago`}
        </span>
      </div>
      <div className="mt-2 grid gap-3 sm:grid-cols-3">
        <div>
          <div className="text-xs text-slate-500">Gas (MQ-2 raw, 0-4095)</div>
          <div className={`text-lg font-semibold ${gas.raw === null ? 'text-slate-500' : ''}`}>{gas.raw === null ? '—' : gas.raw}</div>
          <div className={`text-[11px] ${TONE[gas.tone]}`}>{gas.status}</div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Device gas warning</div>
          <div role={gas.warning ? 'alert' : undefined}
            className={`text-lg font-semibold ${gas.warning ? 'text-red-300' : gas.warning === false ? '' : 'text-slate-500'}`}>
            {gas.warning === null ? 'unknown' : gas.warning ? 'ACTIVE' : 'off'}
          </div>
          <div className="text-[11px] text-slate-500">{gas.warning === null ? 'no recent state report' : 'red LED follows this'}</div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Motion (PIR)</div>
          <div className={`text-lg font-semibold ${motion.motion === null ? 'text-slate-500' : ''}`}>{motion.label}</div>
          <div className="text-[11px] text-slate-500">since the previous report</div>
        </div>
      </div>
    </li>
  )
}

// The fetch time travels with the data so "updated x ago" keeps ticking between polls (as on the Devices page).
const loadDevices = () => api.listDevices().then((devices) => ({ devices, fetchedAt: Date.now() }))

/** Latest gas reading, gas sensor status, the device's own gas warning and motion, per device. Values come only from
 *  the device's latest report; nothing is estimated. */
export default function SensorReadingsPanel() {
  const devices = usePolled(loadDevices, 10000)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const elapsed = devices.data ? Math.max(0, Math.floor((now - devices.data.fetchedAt) / 1000)) : 0
  const list = devices.data?.devices.filter((d) => d.status === 'ACTIVE') ?? []

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="sensor-readings-heading">
      <h3 id="sensor-readings-heading" className="font-semibold">Live sensor readings</h3>
      {!devices.data && !devices.error && <PanelSkeleton lines={2} />}
      {devices.error && (
        <p className="mt-2 text-sm text-red-400" role="alert">{devices.error} <button onClick={devices.reload} className="underline">Try again</button></p>
      )}
      {devices.data && list.length === 0 && (
        <p className="mt-3 rounded-lg bg-slate-900 p-3 text-sm text-slate-400">No devices yet. Pair one on the Devices page.</p>
      )}
      <ul className="mt-3 space-y-2">
        {list.map((d) => <Row key={d.id} d={d} elapsed={elapsed} />)}
      </ul>
    </section>
  )
}
