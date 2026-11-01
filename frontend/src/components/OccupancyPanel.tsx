import { useEffect, useMemo, useState } from 'react'
import { api, ApiError, type Forecast, type OccupancyStatus, type Prediction } from '../api'
import { STATE, browserZone, num, pct, progress, verdict, zoneList } from '../insights'

function Badge({ tone, children }: { tone: string; children: React.ReactNode }) {
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ${tone}`}>{children}</span>
}

function Bar({ have, need }: { have: number; need: number }) {
  const done = have >= need
  return (
    <div className="h-1.5 w-full rounded bg-slate-700" role="progressbar" aria-valuemin={0} aria-valuemax={need} aria-valuenow={Math.min(have, need)}>
      <div className={`h-1.5 rounded ${done ? 'bg-green-500' : 'bg-amber-400'}`} style={{ width: `${Math.min(100, (have / need) * 100)}%` }} />
    </div>
  )
}

/** A 24-bar chart of the forecast. Hatched bars rest on little evidence for that hour of the week. */
function ForecastChart({ forecast }: { forecast: Forecast }) {
  return (
    <div>
      <div className="flex h-28 items-end gap-0.5" role="img" aria-label="Forecast of the chance of motion for each of the next 24 hours">
        {forecast.points.map((p, i) => (
          <div key={p.at} className="flex h-full flex-1 flex-col justify-end"
            title={`${p.dayOfWeek} ${String(p.hour).padStart(2, '0')}:00 · ${pct(p.probability)} chance of motion · evidence: ${p.support} similar slots${p.reliable ? '' : ' (low)'}`}>
            <div
              className={`chart-bar w-full rounded-t-sm ${p.reliable ? 'bg-indigo-400' : 'bg-[repeating-linear-gradient(45deg,#7f8b87,#7f8b87_3px,#3a4749_3px,#3a4749_6px)]'}`}
              style={{ height: `${Math.max(2, p.probability * 100)}%`, animationDelay: `${i * 8}ms` }}
            />
          </div>
        ))}
      </div>
      <div className="mt-1 flex gap-0.5 text-[10px] text-slate-500">
        {forecast.points.map((p, i) => (
          <span key={p.at} className="flex-1 text-center">{i % 3 === 0 ? String(p.hour).padStart(2, '0') : ''}</span>
        ))}
      </div>
    </div>
  )
}

interface Props {
  deviceId: string
  status: OccupancyStatus
  onChanged: () => void
}

export default function OccupancyPanel({ deviceId, status, onChanged }: Props) {
  const zones = useMemo(() => zoneList(), [])
  const [zone, setZone] = useState(status.timeZone ?? browserZone())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const available = status.predictionsAvailable
  // Predictions belong to one trained model; a result only counts while it matches the model currently shown.
  const modelKey = available ? `${deviceId}:${status.trainedAt}` : ''
  const [predictions, setPredictions] = useState<{ key: string; now?: Prediction; forecast?: Forecast; error?: string } | null>(null)
  const shown = predictions && modelKey !== '' && predictions.key === modelKey ? predictions : null

  useEffect(() => {
    if (modelKey === '') return
    let cancelled = false
    Promise.all([api.predictNow(deviceId), api.forecast(deviceId, 24)])
      .then(([n, f]) => !cancelled && setPredictions({ key: modelKey, now: n, forecast: f }))
      .catch((e) => !cancelled && setPredictions({ key: modelKey, error: e instanceof ApiError ? e.message : 'Could not load predictions' }))
    return () => {
      cancelled = true
    }
  }, [modelKey, deviceId])

  const train = async () => {
    setBusy(true)
    setError('')
    try {
      await api.trainOccupancy(deviceId, zone)
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Training failed')
    } finally {
      setBusy(false)
    }
  }

  const steps = progress(status)
  const state = STATE[status.state]
  const m = status.metrics

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="occupancy-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="occupancy-heading" className="font-semibold">Occupancy prediction</h3>
        <Badge tone={state.tone}>{state.label}</Badge>
      </div>
      <p className="mt-1 text-sm text-slate-300">{status.message}</p>
      <p className="mt-1 text-xs text-slate-500">
        "Occupied" here means the PIR motion sensor fired during a {status.requirements.slotMinutes}-minute slot. Someone sitting still may not trigger it.
      </p>

      {/* ---- readiness ---- */}
      <div className="mt-4 rounded-lg bg-slate-900 p-3">
        <h4 className="text-sm font-medium">Readiness</h4>
        {steps ? (
          <ul className="mt-2 space-y-2">
            {steps.map((s) => (
              <li key={s.label} className="text-xs">
                <div className="mb-1 flex justify-between text-slate-400"><span>{s.label}</span><span>{s.have} / {s.need} needed</span></div>
                <Bar have={s.have} need={s.need} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-xs text-slate-400">
            {status.data.motionReadings === 0
              ? 'No motion readings are stored for this device yet.'
              : `${status.data.motionReadings} motion readings stored (${status.data.firstAt ? new Date(status.data.firstAt).toLocaleDateString() : '?'} to ${status.data.lastAt ? new Date(status.data.lastAt).toLocaleDateString() : '?'}).`}{' '}
            Training will check whether that is enough: at least {status.requirements.minUsableSlots} usable {status.requirements.slotMinutes}-minute slots over {status.requirements.minDistinctDays}+ days.
          </p>
        )}
        {status.trainedAt && (
          <p className="mt-2 text-xs text-slate-500">
            Last training run {new Date(status.trainedAt).toLocaleString()}{status.timeZone ? ` · time zone ${status.timeZone}` : ''}
            {status.counts?.dataFrom && status.counts.dataTo ? ` · data ${new Date(status.counts.dataFrom).toLocaleDateString()} to ${new Date(status.counts.dataTo).toLocaleDateString()}` : ''}
          </p>
        )}
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <label className="text-xs text-slate-400">
            Time zone for "time of day"
            <select value={zone} onChange={(e) => setZone(e.target.value)} className="mt-1 block rounded-md border border-slate-600 bg-slate-800 px-2 py-1.5 text-sm text-slate-100">
              {zones.map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          </label>
          <button type="button" onClick={train} disabled={busy}
            className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
            {busy ? 'Training...' : status.state === 'NOT_TRAINED' ? 'Train now' : 'Retrain now'}
          </button>
        </div>
        <p className="mt-2 text-[11px] text-slate-500">
          Training uses the stored history. It runs only when you press this, or about once a day automatically when enough new data has arrived. It never runs on individual readings.
        </p>
        {error && <p className="mt-2 text-sm text-red-400">{error}</p>}
      </div>

      {/* ---- metrics ---- */}
      {m ? (
        <div className="mt-4">
          <h4 className="text-sm font-medium">How good is it? (on unseen recent data)</h4>
          <p className={`mt-1 text-xs ${verdict(m).better ? 'text-green-300' : 'text-red-300'}`}>{verdict(m).text}</p>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-slate-500">
                <tr><th className="py-1 pr-3 font-medium">Measure</th><th className="py-1 pr-3 font-medium">Model</th><th className="py-1 font-medium">Guessing the average</th></tr>
              </thead>
              <tbody className="text-slate-200">
                <tr><td className="py-1 pr-3">Accuracy</td><td className="pr-3">{pct(m.accuracy, 1)}</td><td>{pct(m.baselineAccuracy, 1)}</td></tr>
                <tr><td className="py-1 pr-3">Log loss (lower is better)</td><td className="pr-3">{num(m.logLoss, 3)}</td><td>{num(m.baselineLogLoss, 3)}</td></tr>
                <tr><td className="py-1 pr-3">Precision</td><td className="pr-3">{m.precision === null ? 'n/a' : pct(m.precision, 1)}</td><td>-</td></tr>
                <tr><td className="py-1 pr-3">Recall</td><td className="pr-3">{m.recall === null ? 'n/a' : pct(m.recall, 1)}</td><td>-</td></tr>
                <tr><td className="py-1 pr-3">F1</td><td className="pr-3">{num(m.f1, 3)}</td><td>-</td></tr>
                <tr><td className="py-1 pr-3">AUC (ranking, 0.5 = chance)</td><td className="pr-3">{num(m.auc, 3)}</td><td>0.5</td></tr>
                <tr><td className="py-1 pr-3">Brier score (lower is better)</td><td className="pr-3">{num(m.brier, 3)}</td><td>-</td></tr>
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-[11px] text-slate-500">
            {m.evaluation} Test set: {m.testSlots} slots, {m.testSlotsWithMotion} with motion. "n/a" means the measure is not defined for this data, not zero.
          </p>
        </div>
      ) : (
        <p className="mt-4 text-xs text-slate-500">No accuracy figures are shown because no model has been evaluated. None are ever estimated or made up.</p>
      )}

      {/* ---- predictions ---- */}
      {available && (
        <div className="mt-4">
          <h4 className="text-sm font-medium">Predictions</h4>
          {!shown && <p className="mt-1 text-xs text-slate-400">Loading predictions...</p>}
          {shown?.error && <p className="mt-1 text-sm text-red-400">{shown.error}</p>}
          {shown?.now && (
            <p className="mt-1 text-sm">
              Right now ({shown.now.dayOfWeek} {String(shown.now.hour).padStart(2, '0')}:00 {status.timeZone}):{' '}
              <strong>{shown.now.predictedOccupied ? 'motion likely' : 'quiet likely'}</strong>, {pct(shown.now.probability)} chance of motion
              <span className="text-slate-500"> · confidence {pct(shown.now.confidence)} · evidence {shown.now.support} similar slots{shown.now.reliable ? '' : ' (low: treat with caution)'}</span>
              {shown.now.observedRate !== null && <span className="text-slate-500"> · seen in {pct(shown.now.observedRate)} of them</span>}
            </p>
          )}
          {shown?.forecast && (
            <div className="mt-3">
              <p className="mb-1 text-xs text-slate-400">Chance of motion, next 24 hours (hour of day, {shown.forecast.timeZone})</p>
              <ForecastChart forecast={shown.forecast} />
              <p className="mt-1 text-[11px] text-slate-500">Hatched bars rest on few similar past slots and are less trustworthy.</p>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
