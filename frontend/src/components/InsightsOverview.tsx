import { Fragment, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { MlStatus } from '../api'
import { STATE, fmtCount, fmtDateTime, pct } from '../insights'
import { predictiveApi, type PredictionNow } from '../predictive'
import { AlertIcon, CheckIcon, MotionIcon, SparkIcon } from '../ui/icons'

function Tile({ icon, label, children, footer, tone = 'default' }: { icon: ReactNode; label: string; children: ReactNode; footer?: ReactNode; tone?: 'default' | 'warn' | 'alarm' }) {
  return (
    <div className={`card flex flex-col p-5 ${tone === 'alarm' ? 'border-red-400/40' : tone === 'warn' ? 'border-amber-300/30' : ''}`}>
      <div className="flex items-center justify-between text-sm text-slate-400">
        {label}
        <span aria-hidden="true" className="grid h-8 w-8 place-items-center rounded-lg bg-slate-700 text-slate-300">{icon}</span>
      </div>
      <div className="mt-3 flex-1">{children}</div>
      {footer && <div className="mt-3 text-xs text-slate-400">{footer}</div>}
    </div>
  )
}

const STATE_TEXT = { LIKELY_OCCUPIED: 'Likely home', LIKELY_EMPTY: 'Likely away', UNCERTAIN: 'Uncertain' } as const

/** The prediction for this time of day on a synthetic "today". Only fetched when the model is ready. */
function NowPrediction({ modelVersion }: { modelVersion: number }) {
  const [res, setRes] = useState<{ version: number; now?: PredictionNow } | null>(null)
  useEffect(() => {
    let cancelled = false
    predictiveApi
      .now()
      .then((now) => !cancelled && setRes({ version: modelVersion, now }))
      .catch(() => !cancelled && setRes({ version: modelVersion }))
    return () => {
      cancelled = true
    }
  }, [modelVersion])
  const shown = res && res.version === modelVersion ? res : null
  if (!shown) return <p className="text-sm text-slate-400">Loading...</p>
  if (!shown.now) return <p className="text-sm text-amber-300">The prediction could not be loaded.</p>
  const { presence, at } = shown.now
  return (
    <>
      <p className="text-2xl font-semibold tracking-tight">{STATE_TEXT[presence.state]}</p>
      <p className="mt-1 text-sm text-slate-300">{pct(presence.probability)} probability of being home · confidence {pct(presence.confidence)}</p>
      <p className="mt-1 text-xs text-slate-500">Synthetic home at {at.slice(11, 16)}. Not your real home.</p>
    </>
  )
}

/** The short version of ML Insights: the model, its current prediction, and anomalies. */
export default function InsightsOverview({ status, onOpenAnomalies }: { status: MlStatus; onOpenAnomalies: () => void }) {
  const o = status.occupancy
  const state = STATE[o.state]
  const a = status.anomalies
  const ready = o.state === 'READY'
  const rows: [string, string][] = ready
    ? [
        ['Training samples', `${fmtCount(o.trainingRows)} from ${o.trainingDays} days`],
        ['Dataset readings', fmtCount(o.dataset?.records)],
        ['Last trained', fmtDateTime(o.trainedAt)],
        ['Model version', `v${o.version}`],
        ['Data source', 'Synthetic demonstration data'],
      ]
    : []

  return (
    <div className="space-y-4">
      {o.dataLabel && <p role="status" className="card border-2 px-4 py-3 text-sm font-semibold">{o.dataLabel}</p>}
      {a.critical > 0 && (
        <p role="alert" className="flex items-start gap-3 rounded-2xl border border-red-400/30 bg-red-500/10 p-4 text-sm text-red-200">
          <AlertIcon className="mt-0.5 shrink-0" width={18} height={18} />
          <span>
            <strong>{a.critical} critical {a.critical === 1 ? 'anomaly is' : 'anomalies are'} active.</strong>{' '}
            <button type="button" onClick={onOpenAnomalies} className="underline">Review them</button>
          </span>
        </p>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        <Tile icon={<SparkIcon />} label="Occupancy model" tone={o.state === 'UNAVAILABLE' ? 'alarm' : ready ? 'default' : 'warn'}
          footer={<Link to="/predictive" className="underline">Open predictive demonstration</Link>}>
          <span className={`inline-block rounded-full px-3 py-1 text-sm font-medium ${state.tone}`}>{state.label}</span>
          {ready ? (
            <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
              {rows.map(([k, v]) => (
                <Fragment key={k}><dt className="text-slate-400">{k}</dt><dd className="text-right text-slate-300">{v}</dd></Fragment>
              ))}
            </dl>
          ) : (
            <p className="mt-2 text-sm text-slate-300">{o.message}</p>
          )}
        </Tile>

        <Tile icon={<MotionIcon />} label="Occupancy now" footer={ready ? undefined : 'A prediction appears once the model is ready.'}>
          {ready ? <NowPrediction modelVersion={o.version} /> : <p className="text-sm text-slate-300">No prediction yet.</p>}
        </Tile>

        <Tile icon={a.active > 0 ? <AlertIcon /> : <CheckIcon />} label="Anomalies" tone={a.critical > 0 ? 'alarm' : a.active > 0 ? 'warn' : 'default'}
          footer={<button type="button" onClick={onOpenAnomalies} className="underline">Open anomaly history</button>}>
          <p className="text-2xl font-semibold tracking-tight">{a.active}</p>
          <p className="mt-1 text-sm text-slate-300">
            {a.active === 0 ? 'No active anomalies' : `${a.active} active${a.critical ? ` · ${a.critical} critical` : ''}${a.warning ? ` · ${a.warning} warning` : ''}`}
          </p>
        </Tile>
      </div>
    </div>
  )
}
