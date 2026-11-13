import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Device } from '../api'
import { useAuth } from '../auth'
import { gasView } from '../sensorStatus'
import { liveStatus } from '../telemetry'
import { AlertIcon, CheckIcon, ChevronRightIcon } from '../ui/icons'
import { usePolled } from './usePolled'

type Item = { key: string; text: string; to: string; tone: 'alarm' | 'warn' }

function greeting(hour: number): string {
  if (hour < 5) return 'Good night'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** Greeting, the date, and one clear answer to "does anything need me?". */
export default function HomeHero({ devices, elapsed }: { devices: Device[] | null; elapsed: number }) {
  const { user } = useAuth()
  const gasAlerts = usePolled(api.gasAlerts, 10000)
  const openAlerts = usePolled(api.openSecurityAlerts, 5000)
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30000)
    return () => clearInterval(timer)
  }, [])

  const items = useMemo<Item[]>(() => {
    const list: Item[] = []
    const activeGas = (gasAlerts.data ?? []).filter((a) => a.state === 'ACTIVE')
    for (const a of activeGas) list.push({ key: `gas-${a.id}`, text: `Gas alert at ${a.deviceName}`, to: '/diagnostics#gas', tone: 'alarm' })
    const open = openAlerts.data ?? []
    if (open.length > 0) list.push({ key: 'sec', text: open.length === 1 ? '1 security alert is waiting' : `${open.length} security alerts are waiting`, to: '/security', tone: 'alarm' })
    for (const d of devices ?? []) {
      const live = liveStatus(d, elapsed)
      if (live.connection === 'OFFLINE') list.push({ key: `off-${d.id}`, text: `${d.name} is offline`, to: '/devices', tone: 'warn' })
      else if (gasView(d, live.stale).tone === 'alarm' && activeGas.length === 0) list.push({ key: `gasw-${d.id}`, text: `Gas warning on ${d.name}`, to: '/devices', tone: 'alarm' })
    }
    return list
  }, [gasAlerts.data, openAlerts.data, devices, elapsed])

  const loading = devices === null || (!gasAlerts.data && !gasAlerts.error) || (!openAlerts.data && !openAlerts.error)
  const needsAttention = items.length > 0

  return (
    <section aria-labelledby="home-heading" className="card relative overflow-hidden p-5 sm:p-6">
      <div aria-hidden="true" className="pointer-events-none absolute -right-16 -top-20 h-56 w-56 rounded-full bg-indigo-400/10 blur-3xl" />
      <div className="relative flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm text-slate-400">
            {now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })} · {now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </p>
          <h1 id="home-heading" className="mt-1 text-2xl font-semibold tracking-tight sm:text-3xl">
            {greeting(now.getHours())}, {user?.username}
          </h1>
        </div>
        <div
          role="status"
          className={`inline-flex items-center gap-2 rounded-full border px-3.5 py-1.5 text-sm font-medium ${
            loading ? 'border-slate-700 text-slate-400'
            : needsAttention ? 'border-amber-400/40 bg-amber-400/10 text-amber-300'
            : 'border-green-400/30 bg-green-400/10 text-green-300'
          }`}
        >
          {loading ? 'Checking your home…' : needsAttention ? <><AlertIcon width={16} height={16} />Needs attention</> : <><CheckIcon width={16} height={16} />All clear</>}
        </div>
      </div>

      {!loading && needsAttention && (
        <ul className="relative mt-4 grid gap-2 sm:grid-cols-2">
          {items.slice(0, 4).map((i) => (
            <li key={i.key}>
              <Link to={i.to} className={`flex items-center justify-between gap-2 rounded-lg border px-3 py-2.5 text-sm hover:bg-slate-700/50 ${i.tone === 'alarm' ? 'border-red-400/40 text-red-200' : 'border-amber-400/30 text-amber-200'}`}>
                <span>{i.text}</span>
                <ChevronRightIcon width={16} height={16} />
              </Link>
            </li>
          ))}
        </ul>
      )}
      {!loading && !needsAttention && devices && devices.length > 0 && (
        <p className="relative mt-3 text-sm text-slate-400">Everything is running normally and no alerts are waiting.</p>
      )}
    </section>
  )
}
