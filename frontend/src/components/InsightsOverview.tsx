import { useEffect, useState, type ReactNode } from 'react'
import { api, type MlStatus, type Prediction } from '../api'
import { STATE, fmtCount, pct, progress } from '../insights'
import { AlertIcon, CheckIcon, MotionIcon, PulseIcon, SparkIcon } from '../ui/icons'

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

/** The current chance-of-motion read-out. Only fetched when the server says a valid model exists. */
function NowPrediction({ deviceId, modelKey, zone }: { deviceId: string; modelKey: string; zone: string | null }) {
  const [res, setRes] = useState<{ key: string; p?: Prediction } | null>(null)
  useEffect(() => {
    let cancelled = false
    Promise.resolve()
      .then(() => api.predictNow(deviceId))
      .then((p) => !cancelled && setRes({ key: modelKey, p }))
      .catch(() => !cancelled && setRes({ key: modelKey }))
    return () => {
      cancelled = true
    }
  }, [deviceId, modelKey])
  const shown = res && res.key === modelKey ? res : null
  if (!shown) return <p className="text-sm text-slate-400">Loading...</p>
  if (!shown.p) return <p className="text-sm text-amber-300">The prediction could not be loaded.</p>
  const p = shown.p
  return (
    <>
      <p className="text-2xl font-semibold tracking-tight">{p.predictedOccupied ? 'Motion likely' : 'Quiet likely'}</p>
      <p className="mt-1 text-sm text-slate-300">{pct(p.probability)} chance of motion this hour{zone ? ` (${zone})` : ''}</p>
      <p className="mt-1 text-xs text-slate-500">Based on {p.support} similar past slots{p.reliable ? '' : ' (little evidence, treat with caution)'}.</p>
    </>
  )
}

/** The short version of ML Insights: model health, data progress, anomalies and (only with a valid model) the prediction. */
export default function InsightsOverview({ deviceId, status, onOpenAnomalies }: { deviceId: string; status: MlStatus; onOpenAnomalies: () => void }) {
  const o = status.occupancy
  const state = STATE[o.state]
  const steps = progress(o)
  const worst = steps ? steps.reduce((a, b) => (a.have / a.need <= b.have / b.need ? a : b)) : null
  const ready = steps ? steps.every((s) => s.have >= s.need) : false
  const a = status.anomalies
  const modelKey = o.predictionsAvailable ? `${deviceId}:${o.trainedAt}` : ''

  return (
    <div className="space-y-4">
      {o.state === 'LOW_QUALITY' && (
        <p role="alert" className="flex items-start gap-3 rounded-2xl border border-red-400/30 bg-red-500/10 p-4 text-sm text-red-200">
          <AlertIcon className="mt-0.5 shrink-0" width={18} height={18} />
          <span><strong>The occupancy model is low quality.</strong> It was not clearly better than guessing the average, so treat its predictions as unreliable.</span>
        </p>
      )}
      {a.critical > 0 && (
        <p role="alert" className="flex items-start gap-3 rounded-2xl border border-red-400/30 bg-red-500/10 p-4 text-sm text-red-200">
          <AlertIcon className="mt-0.5 shrink-0" width={18} height={18} />
          <span>
            <strong>{a.critical} critical {a.critical === 1 ? 'anomaly is' : 'anomalies are'} active.</strong>{' '}
            <button type="button" onClick={onOpenAnomalies} className="underline">Review them</button>
          </span>
        </p>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Tile icon={<SparkIcon />} label="Occupancy model" tone={o.state === 'LOW_QUALITY' ? 'alarm' : o.state === 'READY' ? 'default' : 'warn'}
          footer={o.modelVersion > 0 ? `Version ${o.modelVersion}` : state.hint}>
          <span className={`inline-block rounded-full px-3 py-1 text-sm font-medium ${state.tone}`}>{state.label}</span>
          <p className="mt-2 text-sm text-slate-300">{o.message}</p>
        </Tile>

        <Tile icon={<PulseIcon />} label="Data collection"
          footer={worst ? `Needs ${o.requirements.minUsableSlots} usable ${o.requirements.slotMinutes}-minute slots over ${o.requirements.minDistinctDays}+ days` : undefined}>
          {worst ? (
            <>
              <p className="text-2xl font-semibold tracking-tight">{ready ? 'Enough data' : `${fmtCount(worst.have)} / ${fmtCount(worst.need)}`}</p>
              <p className="mt-1 text-sm text-slate-300">{ready ? 'All training requirements are met.' : worst.label}</p>
              <div className="mt-3 h-1.5 rounded bg-slate-700" role="progressbar" aria-label="Data collection progress" aria-valuemin={0} aria-valuemax={worst.need} aria-valuenow={Math.min(worst.have, worst.need)}>
                <div className={`h-1.5 rounded ${ready ? 'bg-green-500' : 'bg-indigo-400'}`} style={{ width: `${Math.min(100, (worst.have / worst.need) * 100)}%` }} />
              </div>
            </>
          ) : (
            <p className="text-sm text-slate-300">{o.data.motionReadings === 0 ? 'No motion readings stored yet.' : `${fmtCount(o.data.motionReadings)} motion readings stored.`}</p>
          )}
        </Tile>

        <Tile icon={<MotionIcon />} label="Occupancy now" footer={o.predictionsAvailable ? undefined : 'Predictions appear once a valid model exists.'}>
          {o.predictionsAvailable ? (
            <NowPrediction deviceId={deviceId} modelKey={modelKey} zone={o.timeZone} />
          ) : (
            <p className="text-sm text-slate-300">No prediction yet. Nothing is guessed before a model is trained.</p>
          )}
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
