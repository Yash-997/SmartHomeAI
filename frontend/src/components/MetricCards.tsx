import type { ReactNode } from 'react'
import type { Device } from '../api'
import { gasView, motionView } from '../sensorStatus'
import { liveStatus } from '../telemetry'
import AnimatedNumber from '../ui/AnimatedNumber'
import { DropletIcon, FlameIcon, MotionIcon, ThermometerIcon } from '../ui/icons'

type Tone = 'ok' | 'warn' | 'alarm' | 'muted'

const DOT: Record<Tone, string> = { ok: 'bg-green-400', warn: 'bg-amber-400', alarm: 'bg-red-400', muted: 'bg-slate-500' }
const TEXT: Record<Tone, string> = { ok: 'text-slate-400', warn: 'text-amber-300', alarm: 'text-red-300', muted: 'text-slate-500' }

function Metric({ icon, label, value, status, tone, stale, extra }: {
  icon: ReactNode
  label: string
  value: ReactNode
  status: string
  tone: Tone
  stale: boolean
  extra?: string
}) {
  return (
    <div className={`card lift relative overflow-hidden p-4 sm:p-6 ${stale ? 'opacity-60' : ''} ${tone === 'alarm' ? '!border-red-400/50' : ''}`}>
      <div aria-hidden="true" className={`pointer-events-none absolute -right-10 -top-10 h-28 w-28 rounded-full blur-2xl ${tone === 'alarm' ? 'bg-red-500/15' : tone === 'warn' ? 'bg-amber-400/10' : 'bg-indigo-400/10'}`} />
      <div className="relative flex items-center justify-between">
        <span className="text-sm text-slate-400">{label}</span>
        <span aria-hidden="true" className={`grid h-9 w-9 place-items-center rounded-xl ${tone === 'alarm' ? 'bg-red-500/15 text-red-300' : tone === 'warn' ? 'bg-amber-400/15 text-amber-300' : 'bg-indigo-400/12 text-indigo-300'}`}>{icon}</span>
      </div>
      <div className="relative mt-4 text-4xl font-semibold leading-none tracking-tight">{value}</div>
      <div className={`relative mt-3 flex items-center gap-2 text-xs ${TEXT[tone]}`}>
        <span key={status} aria-hidden="true" className={`status-dot !h-1.5 !w-1.5 ${DOT[tone].replace('bg-', 'text-')} ${tone === 'alarm' || tone === 'warn' ? 'status-dot-live' : ''}`} />
        <span>{status}</span>
        {extra && <span className="text-slate-500">· {extra}</span>}
      </div>
    </div>
  )
}

const none = <span className="text-slate-500">&mdash;</span>
const unit = (u: string) => <span className="ml-1 text-lg font-normal text-slate-400">{u}</span>

function temperatureStatus(c: number): { status: string; tone: Tone } {
  if (c < 18) return { status: 'Cool', tone: 'ok' }
  if (c <= 27) return { status: 'Comfortable', tone: 'ok' }
  if (c <= 32) return { status: 'Warm', tone: 'warn' }
  return { status: 'Hot', tone: 'alarm' }
}

function humidityStatus(p: number): { status: string; tone: Tone } {
  if (p < 30) return { status: 'Dry', tone: 'warn' }
  if (p <= 60) return { status: 'Comfortable', tone: 'ok' }
  if (p <= 75) return { status: 'Humid', tone: 'warn' }
  return { status: 'Very humid', tone: 'alarm' }
}

/** The four live readings of the home, taken only from the device's latest report. Nothing is estimated. */
export default function MetricCards({ device, elapsed }: { device: Device; elapsed: number }) {
  const { stale } = liveStatus(device, elapsed)
  const t = device.latestTelemetry
  const gas = gasView(device, stale)
  const motion = motionView(device, stale)

  const temp = t?.temperatureC ?? null
  const hum = t?.humidityPct ?? null
  const tempState = temp === null ? { status: 'No reading', tone: 'muted' as Tone } : temperatureStatus(temp)
  const humState = hum === null ? { status: 'No reading', tone: 'muted' as Tone } : humidityStatus(hum)

  const gasLabel =
    gas.raw === null ? 'No data'
    : gas.tone === 'alarm' ? 'Warning'
    : gas.tone === 'warn' ? 'Check sensor'
    : stale ? 'Last known'
    : 'Normal'
  const gasTone: Tone = gas.raw === null ? 'muted' : gas.tone
  const gasStatus =
    gas.raw === null ? 'No reading'
    : gas.tone === 'alarm' ? 'Gas warning on the device'
    : gas.tone === 'warn' ? 'Reads 0, check the sensor wiring'
    : stale ? 'Stale reading'
    : 'Air is clear'

  return (
    <section aria-label="Live environment" className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
      <Metric icon={<ThermometerIcon />} label="Temperature" stale={stale} status={tempState.status} tone={stale && temp !== null ? 'muted' : tempState.tone}
        value={temp === null ? none : <><AnimatedNumber value={temp} />{unit('°C')}</>} />
      <Metric icon={<DropletIcon />} label="Humidity" stale={stale} status={humState.status} tone={stale && hum !== null ? 'muted' : humState.tone}
        value={hum === null ? none : <><AnimatedNumber value={hum} decimals={0} />{unit('%')}</>} />
      <Metric icon={<MotionIcon />} label="Motion" stale={stale}
        status={motion.motion === null ? 'No reading' : motion.motion ? 'Motion detected' : 'No motion'} tone={motion.motion === null || stale ? 'muted' : motion.motion ? 'warn' : 'ok'}
        value={motion.motion === null ? none : motion.motion ? 'Detected' : 'Clear'} />
      <Metric icon={<FlameIcon />} label="Gas" stale={stale && gas.tone !== 'alarm'} status={gasStatus} tone={gasTone}
        extra={gas.raw === null ? undefined : `raw ${gas.raw}`}
        value={gas.raw === null ? none : gasLabel} />
    </section>
  )
}
