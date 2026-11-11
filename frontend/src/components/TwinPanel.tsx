import { api, type SyncState } from '../api'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const SYNC: Record<SyncState, { label: string; tone: string }> = {
  IN_SYNC: { label: 'In sync', tone: 'bg-green-500/20 text-green-300' },
  PENDING: { label: 'Waiting for device', tone: 'bg-indigo-500/20 text-indigo-300' },
  DRIFT: { label: 'Differs', tone: 'bg-amber-500/20 text-amber-300' },
  UNREPORTED: { label: 'Cannot verify', tone: 'bg-slate-600 text-slate-200' },
  UNKNOWN: { label: 'Outcome unknown', tone: 'bg-amber-500/20 text-amber-300' },
  NO_DESIRED: { label: 'Nothing to compare', tone: 'bg-slate-700 text-slate-300' },
}

function age(seconds: number | null): string {
  if (seconds === null) return 'never'
  if (seconds < 90) return `${seconds}s ago`
  if (seconds < 5400) return `${Math.round(seconds / 60)} min ago`
  return `${Math.round(seconds / 3600)} h ago`
}

export default function TwinPanel() {
  const { data, error, reload } = usePolled(api.twins, 15000)

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="twin-heading">
      <div className="flex items-center justify-between">
        <h3 id="twin-heading" className="font-semibold">Device twins</h3>
        <button type="button" onClick={reload} className="rounded-md bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600">Refresh</button>
      </div>
      <p className="mt-1 text-xs text-slate-500">What was last confirmed by each device, what it should be doing now (forced LED modes are temporary on the device), what it reports, and whether they agree.</p>

      {!data && !error && <PanelSkeleton lines={3} />}
      {error && <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}
      {data && data.length === 0 && <p className="mt-3 rounded-lg bg-slate-900 p-3 text-sm text-slate-400">No devices yet. Pair one on the Devices page.</p>}

      <ul className="mt-3 space-y-3">
        {data?.map((t) => (
          <li key={t.deviceId} className="rounded-lg bg-slate-900 p-3 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-medium">{t.name}</span>
              <span className="text-xs text-slate-400">
                {t.connection.replace('_', ' ').toLowerCase()} · last heard {age(t.lastSeenAgeSeconds)} · last reading {age(t.lastReadingAgeSeconds)}{t.readingStale ? ' (stale)' : ''}
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400">{t.summary}</p>
            <table className="mt-2 w-full text-left text-xs">
              <thead className="text-slate-500">
                <tr><th className="py-1 pr-2 font-medium">Output</th><th className="pr-2 font-medium">Confirmed request</th><th className="pr-2 font-medium">Expected now</th><th className="pr-2 font-medium">Device reports</th><th className="font-medium">State</th></tr>
              </thead>
              <tbody>
                {t.outputs.map((o) => (
                  <tr key={o.action} className="border-t border-slate-800 align-top">
                    <td className="py-1 pr-2">{o.label}</td>
                    <td className="pr-2">{o.desired ?? 'none'}{o.desiredBy ? <span className="text-slate-500"> by {o.desiredBy}</span> : null}{o.requested ? <span className="text-indigo-300"> (asking for {o.requested})</span> : null}</td>
                    <td className="pr-2">{o.expected ?? '-'}</td>
                    <td className="pr-2">{o.reportedMode === null ? 'no report' : `${o.reportedMode}${o.reportedLit === null ? '' : o.reportedLit ? ', lit' : ', not lit'}`}{o.reportedStale && o.reportedMode !== null ? ' (old)' : ''}</td>
                    <td>
                      <span className={`rounded px-1.5 py-0.5 ${SYNC[o.sync].tone}`}>{SYNC[o.sync].label}</span>
                      {o.note && <span className="mt-0.5 block text-slate-500">{o.note}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {t.gasWarningActive !== null && (
              <p role={t.gasWarningActive ? 'alert' : undefined} className={`mt-2 text-xs ${t.gasWarningActive ? 'alert-critical rounded bg-red-500/10 p-1.5 font-medium text-red-300' : 'text-slate-500'}`}>
                Gas warning reported by the device: {t.gasWarningActive ? 'ACTIVE. Follow the device warning and local emergency guidance.' : 'not active'}
              </p>
            )}
            {t.pendingCommands.length > 0 && (
              <p className="mt-1 text-xs text-indigo-300">Pending: {t.pendingCommands.map((c) => `${c.action} ${c.value} (${c.status.toLowerCase()})`).join(', ')}</p>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
