import { api, type Level } from '../api'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const TONE: Record<Level, string> = {
  OK: 'bg-green-500/20 text-green-300',
  DEGRADED: 'bg-amber-500/20 text-amber-300',
  DOWN: 'bg-red-500/20 text-red-300',
}
const SENSOR_TONE = { FRESH: 'text-green-300', STALE: 'text-amber-300', NEVER: 'text-red-300' } as const

function Badge({ level }: { level: Level }) {
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ${TONE[level]}`}>{level}</span>
}

function age(seconds: number | null): string {
  if (seconds === null) return 'never'
  if (seconds < 90) return `${seconds}s ago`
  if (seconds < 5400) return `${Math.round(seconds / 60)} min ago`
  return `${Math.round(seconds / 3600)} h ago`
}

export default function SystemStatusPanel() {
  const { data, error, reload } = usePolled(api.systemStatus)

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="status-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="status-heading" className="font-semibold">System status {data && <Badge level={data.overall} />}</h3>
        <button type="button" onClick={reload} className="rounded-md bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600">Check now</button>
      </div>

      {!data && !error && <PanelSkeleton lines={4} />}
      {error && <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}{data ? ' (showing the last result)' : ''}</p>}

      {data && (
        <div className="mt-3 space-y-4 text-sm">
          {data.issues.length === 0 ? (
            <p className="text-green-300">Everything checked is working.</p>
          ) : (
            <ul className="space-y-1">
              {data.issues.map((i, n) => (
                <li key={n} className="flex items-start gap-2">
                  <Badge level={i.level} />
                  <span><span className="text-slate-500">{i.area}: </span>{i.message}</span>
                </li>
              ))}
            </ul>
          )}

          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
            <div className="rounded bg-slate-900 p-2"><dt className="text-slate-500">Database</dt><dd>{data.database.detail}</dd></div>
            <div className="rounded bg-slate-900 p-2"><dt className="text-slate-500">Message broker</dt><dd>{data.mqtt.detail}</dd></div>
            <div className="rounded bg-slate-900 p-2">
              <dt className="text-slate-500">Commands, {data.commands.windowHours} h</dt>
              <dd>{data.commands.total === 0 ? 'none sent' : `${data.commands.total} sent, ${Math.round((data.commands.failureRate ?? 0) * 100)}% problems`}</dd>
            </div>
            <div className="rounded bg-slate-900 p-2">
              <dt className="text-slate-500">Assistant</dt>
              <dd>{data.assistant.modelConfigured ? `${data.assistant.provider} / ${data.assistant.model}` : 'references only'}</dd>
            </div>
          </dl>

          {data.devices.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-slate-500">
                  <tr><th className="py-1 pr-3 font-medium">Device</th><th className="pr-3 font-medium">Connection</th><th className="pr-3 font-medium">Sensors (newest value)</th><th className="font-medium">ML</th></tr>
                </thead>
                <tbody>
                  {data.devices.map((d) => {
                    const s = data.sensors.find((x) => x.deviceId === d.deviceId)
                    const m = data.ml.find((x) => x.deviceId === d.deviceId)
                    return (
                      <tr key={d.deviceId} className="border-t border-slate-700 align-top">
                        <td className="py-1 pr-3">{d.name}</td>
                        <td className="pr-3">{d.connection.replace('_', ' ').toLowerCase()} · {age(d.lastSeenAgeSeconds)}</td>
                        <td className="pr-3">
                          {s && Object.entries(s.sensors).map(([name, st]) => (
                            <span key={name} className={`mr-2 ${SENSOR_TONE[st.state]}`}>{name} {st.state === 'NEVER' ? 'never' : age(st.ageSeconds)}</span>
                          ))}
                        </td>
                        <td>{m ? `${m.occupancyState.replace('_', ' ').toLowerCase()}${m.activeAnomalies ? `, ${m.activeAnomalies} anomalies` : ''}` : ''}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

          {data.commands.recentProblems.length > 0 && (
            <div>
              <h4 className="text-xs font-medium text-slate-400">Recent command problems</h4>
              <ul className="mt-1 space-y-0.5 text-xs">
                {data.commands.recentProblems.map((c, n) => (
                  <li key={n}>{new Date(c.at).toLocaleString()} · {c.action} {c.value} · {c.status.toLowerCase()}{c.detail ? ` (${c.detail})` : ''}</li>
                ))}
              </ul>
            </div>
          )}
          <p className="text-[11px] text-slate-500">
            Checked {new Date(data.checkedAt).toLocaleTimeString()}. {data.assistant.detail} This view does not test the language model itself.
          </p>
        </div>
      )}
    </section>
  )
}
