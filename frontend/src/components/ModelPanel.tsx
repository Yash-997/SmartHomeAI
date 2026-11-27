import { useState } from 'react'
import { ApiError, type OccupancyStatus } from '../api'
import { fmtCount, fmtDate } from '../insights'
import { predictiveApi } from '../predictive'
import { EvaluationPanel } from './PredictivePanels'

const button = 'btn-secondary rounded-lg px-3 py-2 text-sm font-medium disabled:opacity-50'
const readable = (name: string) => name.toLowerCase().replaceAll('_', ' ')

/** The synthetic dataset, the algorithm chosen for each target, training actions and the measured evaluation. */
export default function ModelPanel({ status, onChanged }: { status: OccupancyStatus; onChanged: () => void }) {
  const [seed, setSeed] = useState(status.dataset?.seed ?? 42)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const run = (label: string, request: () => Promise<unknown>) => {
    setBusy(label)
    setError('')
    request()
      .then(onChanged)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Request failed'))
      .finally(() => setBusy(''))
  }
  const d = status.dataset
  return (
    <div className="space-y-4">
      {d && (
        <p className="text-sm text-slate-300">
          {d.days} synthetic days ({fmtDate(d.from)} to {fmtDate(d.to)}, {d.timeZone}): {fmtCount(d.records)} one-minute readings,{' '}
          {d.arrivals} arrivals, {d.departures} departures, {fmtCount(d.offlineMinutes)} simulated offline minutes. Seed {d.seed}.
        </p>
      )}
      {Object.keys(status.algorithms).length > 0 && (
        <ul className="text-sm text-slate-300">
          {Object.entries(status.algorithms).map(([target, algorithm]) => (
            <li key={target}>{target}: <b>{readable(algorithm)}</b></li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-end gap-2">
        <button type="button" className={button} disabled={busy !== ''} onClick={() => run('Training…', predictiveApi.train)}>Retrain model</button>
        <label className="flex flex-col text-xs text-slate-400">
          Dataset seed
          <input type="number" className="field-input w-28 rounded-lg px-3 py-2 text-sm" value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
        </label>
        <button type="button" className={button} disabled={busy !== ''} onClick={() => run('Generating and training…', () => predictiveApi.regenerate(seed))}>
          Regenerate 30-day dataset
        </button>
        <span role="status" className="text-xs text-slate-400">{busy}</span>
      </div>
      <p className="text-xs text-slate-500">Both actions use synthetic data only and affect the shared model. Live ESP32 readings are never used for training.</p>
      {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
      <EvaluationPanel key={status.version} />
    </div>
  )
}
