import { api, type EmailDelivery, type GasAlert } from '../api'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

const DELIVERY_TONE: Record<EmailDelivery, string> = {
  SENT: 'bg-emerald-500/20 text-emerald-200',
  PENDING: 'bg-slate-600 text-slate-200',
  FAILED: 'bg-red-500/30 text-red-200',
  SKIPPED: 'bg-amber-500/20 text-amber-200',
}

function Delivery({ label, status, at, error }: { label: string; status: EmailDelivery | null; at: string | null; error: string | null }) {
  if (!status) return <span className="text-slate-500">{label}: not due yet</span>
  return (
    <span title={error ?? undefined}>
      {label}: <span className={`rounded px-1.5 py-0.5 ${DELIVERY_TONE[status]}`}>{status.toLowerCase()}</span>
      {at && <span className="text-slate-500"> {new Date(at).toLocaleTimeString()}</span>}
      {error && <span className="block text-slate-400">{error}</span>}
    </span>
  )
}

function Incident({ a }: { a: GasAlert }) {
  const active = a.state === 'ACTIVE'
  return (
    <li className={`rounded px-3 py-2 text-xs ${active ? 'bg-red-500/10 ring-1 ring-red-500/40' : 'bg-slate-900'}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 ${active ? 'bg-red-500/30 text-red-200' : 'bg-slate-600'}`}>{active ? 'active' : 'recovered'}</span>
        <span className="font-medium">{a.deviceName}</span>
        <span className="text-slate-400">peak {a.peakGasRaw} · now {a.lastGasRaw}</span>
      </div>
      <p className="mt-1 text-slate-500">
        Started {new Date(a.startedAt).toLocaleString()}
        {a.recoveredAt ? ` · ended ${new Date(a.recoveredAt).toLocaleString()}` : ''}
      </p>
      <p className="mt-1 space-x-3">
        <Delivery label="Alert email" status={a.alertEmailStatus} at={a.alertEmailSentAt} error={a.alertEmailError} />
        <Delivery label="Recovery email" status={a.recoveryEmailStatus} at={a.recoveryEmailSentAt} error={a.recoveryEmailError} />
      </p>
    </li>
  )
}

/** Gas/smoke (MQ-2) incidents and whether their emails were delivered. Read-only: thresholds are set in .env. */
export default function GasAlertsPanel() {
  const config = usePolled(api.gasAlertConfig, 60000)
  const alerts = usePolled(api.gasAlerts, 10000)
  const c = config.data
  const active = alerts.data?.filter((a) => a.state === 'ACTIVE').length ?? 0

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="gas-alerts-heading">
      <h3 id="gas-alerts-heading" className="font-semibold">
        Gas &amp; smoke alerts {active > 0 && <span className="ml-1 rounded bg-red-500/30 px-2 py-0.5 text-xs text-red-200">{active} active</span>}
      </h3>

      {c && (
        <div className="mt-2 space-y-1 text-xs text-slate-400">
          <p>
            {c.enabled ? 'Emails an alert' : 'Alerts are switched off. Would alert'} when the MQ-2 reading reaches {c.threshold} (raw, 0-4095) and a recovery email once it stays below {c.recoveryThreshold}.
          </p>
          <p>{c.recipient ? <>Sent to <span className="text-slate-200">{c.recipient}</span>.</> : 'Your account has no email address, so nothing can be mailed.'}</p>
          {!c.mailConfigured && (
            <p className="rounded bg-amber-500/10 p-2 text-amber-200" role="status">
              Gmail is not set up (MAIL_USERNAME / MAIL_PASSWORD in .env). Incidents are still recorded here, but no email is sent.
            </p>
          )}
        </div>
      )}
      {config.error && !c && <p className="mt-2 text-sm text-red-400" role="alert">{config.error}</p>}

      {!alerts.data && !alerts.error && <PanelSkeleton lines={2} />}
      {alerts.error && <p className="mt-2 text-sm text-red-400" role="alert">{alerts.error} <button onClick={alerts.reload} className="underline">Try again</button></p>}
      {alerts.data && alerts.data.length === 0 && (
        <p className="mt-3 rounded-lg bg-slate-900 p-3 text-sm text-slate-400">No gas or smoke incidents. Nothing has crossed the threshold.</p>
      )}
      <ul className="mt-3 max-h-72 space-y-2 overflow-y-auto">
        {alerts.data?.map((a) => <Incident key={a.id} a={a} />)}
      </ul>
    </section>
  )
}
