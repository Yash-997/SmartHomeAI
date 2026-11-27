import { useEffect, useState } from 'react'
import { api, ApiError, type Connectivity } from '../api'
import { fmtDateTime } from '../insights'

/** When this physical device was actually reachable, judged from its real messages. */
export default function ConnectivityPanel({ deviceId }: { deviceId: string }) {
  const [conn, setConn] = useState<{ forDevice: string; data?: Connectivity; error?: string } | null>(null)
  const shown = conn && conn.forDevice === deviceId ? conn : null

  useEffect(() => {
    let cancelled = false
    api
      .connectivity(deviceId, 8)
      .then((c) => !cancelled && setConn({ forDevice: deviceId, data: c }))
      .catch((e) => !cancelled && setConn({ forDevice: deviceId, error: e instanceof ApiError ? e.message : 'Could not load connectivity' }))
    return () => {
      cancelled = true
    }
  }, [deviceId])

  if (!shown) return <p className="text-sm text-slate-400">Loading...</p>
  if (!shown.data) return <p className="text-sm text-red-400">{shown.error}</p>
  const c = shown.data
  return (
    <div>
      <p className="text-sm text-slate-400">
        Now <strong className={c.currentState === 'ONLINE' ? 'text-green-300' : 'text-amber-300'}>{c.currentState.replace('_', ' ').toLowerCase()}</strong>
        {' · '}{c.offlineEventsLast7Days} disconnection{c.offlineEventsLast7Days === 1 ? '' : 's'} in the last 7 days
      </p>
      {c.events.length === 0 ? (
        <p className="mt-1 text-xs text-slate-500">No changes recorded yet. Changes are checked once a minute.</p>
      ) : (
        <ul className="mt-2 space-y-1 text-xs">
          {c.events.map((e) => (
            <li key={`${e.changedAt}-${e.state}`} className="flex flex-wrap gap-x-2">
              <span className={`w-16 font-medium ${e.state === 'ONLINE' ? 'text-green-300' : 'text-amber-300'}`}>{e.state.toLowerCase()}</span>
              <span className="tabular-nums text-slate-300">{fmtDateTime(e.changedAt)}</span>
              <span className="text-slate-500">{e.reason}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
