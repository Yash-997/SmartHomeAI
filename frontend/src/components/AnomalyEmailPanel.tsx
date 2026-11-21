import { api, type AnomalyEmail } from '../api'
import { KIND, SEVERITY } from '../insights'
import { PanelSkeleton } from '../ui/Skeleton'
import { Delivery } from './GasAlertsPanel'
import { usePolled } from './usePolled'

function Item({ a }: { a: AnomalyEmail }) {
  const critical = a.severity === 'CRITICAL'
  return (
    <li className={`rounded px-3 py-2 text-xs ${critical ? 'bg-red-500/10 ring-1 ring-red-500/40' : 'bg-slate-900'}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 ${SEVERITY[a.severity].tone}`}>{SEVERITY[a.severity].label}</span>
        <span className="font-medium">{a.deviceName}</span>
        <span className="text-slate-400">{a.sensor} · {KIND[a.kind]}</span>
      </div>
      <p className="mt-1 text-slate-400">
        Value {a.value} · expected {a.expected}
      </p>
      <p className="mt-1 text-slate-500">
        Detected {new Date(a.detectedAt).toLocaleString()} · device ID {a.deviceId}
      </p>
      <p className="mt-1">
        <Delivery label="Anomaly email" status={a.emailStatus} at={a.emailAt} error={a.emailError} />
      </p>
    </li>
  )
}

/** Anomaly notification emails and whether they were delivered. Read-only: settings are in .env. */
export default function AnomalyEmailPanel() {
  const config = usePolled(api.anomalyEmailConfig, 60000)
  const emails = usePolled(api.anomalyEmails, 10000)
  const c = config.data
  const failed = emails.data?.filter((a) => a.emailStatus === 'FAILED').length ?? 0

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="anomaly-email-heading">
      <h3 id="anomaly-email-heading" className="font-semibold">
        Anomaly alerts {failed > 0 && <span className="ml-1 rounded bg-red-500/30 px-2 py-0.5 text-xs text-red-200">{failed} failed</span>}
      </h3>

      {c && (
        <div className="mt-2 space-y-1 text-xs text-slate-400">
          <p>
            {c.enabled ? 'Emails an alert' : 'Anomaly emails are switched off. Would email'} once per detected anomaly of severity {c.minSeverity.toLowerCase()} or higher.
            The same problem on the same device is not emailed again for {c.cooldownMinutes} minutes.
          </p>
          <p>{c.recipient ? <>Sent to <span className="text-slate-200">{c.recipient}</span>.</> : 'Your account has no email address, so nothing can be mailed.'}</p>
          {!c.mailConfigured && (
            <p className="rounded bg-amber-500/10 p-2 text-amber-200" role="status">
              Gmail is not set up (MAIL_USERNAME / MAIL_PASSWORD in .env). Anomalies are still recorded and reported, but no email is sent.
            </p>
          )}
        </div>
      )}
      {config.error && !c && <p className="mt-2 text-sm text-red-400" role="alert">{config.error}</p>}

      {!emails.data && !emails.error && <PanelSkeleton lines={2} />}
      {emails.error && <p className="mt-2 text-sm text-red-400" role="alert">{emails.error} <button onClick={emails.reload} className="underline">Try again</button></p>}
      {emails.data && emails.data.length === 0 && (
        <p className="mt-3 rounded-lg bg-slate-900 p-3 text-sm text-slate-400">No anomaly emails yet. Nothing has needed one.</p>
      )}
      <ul className="mt-3 max-h-72 space-y-2 overflow-y-auto">
        {emails.data?.map((a) => <Item key={a.id} a={a} />)}
      </ul>
    </section>
  )
}
