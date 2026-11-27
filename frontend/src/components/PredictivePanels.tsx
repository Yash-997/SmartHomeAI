import { useEffect, useState, type ReactNode } from 'react'
import { ApiError, type Command } from '../api'
import { pct, predictiveApi, type Evaluation, type Score, type Settings, type Simulation } from '../predictive'

const input = 'field-input rounded-lg px-3 py-2 text-sm'
const button = 'btn-secondary rounded-lg px-3 py-2 text-sm font-medium'
const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Request failed')

export function SettingsForm({ settings, onApply }: { settings: Settings; onApply: (s: Settings) => void }) {
  const [s, setS] = useState(settings)
  const number = (key: 'onThreshold' | 'offThreshold' | 'arrivalThreshold' | 'confidenceThreshold' | 'cooldownSeconds' | 'preArrivalTimeoutMinutes', label: string, step: number) => (
    <label className="flex flex-col text-xs text-slate-400">
      {label}
      <input type="number" min={0} step={step} className={`${input} w-32`} value={s[key]} onChange={(e) => setS({ ...s, [key]: Number(e.target.value) })} />
    </label>
  )
  const toggle = (key: 'nightEnabled' | 'predictiveEnabled', label: string) => (
    <label className="flex items-center gap-2 text-sm text-slate-300">
      <input type="checkbox" checked={s[key]} onChange={(e) => onApply({ ...settings, [key]: e.target.checked })} />
      {label}
    </label>
  )
  return (
    <form className="card space-y-3 p-4" onSubmit={(e) => { e.preventDefault(); onApply(s) }}>
      <h2 className="text-sm font-semibold text-slate-100">Decision settings</h2>
      <div className="flex flex-wrap gap-4">{toggle('nightEnabled', 'Night Mode')}{toggle('predictiveEnabled', 'Predictive lighting')}</div>
      <div className="flex flex-wrap gap-3">
        {(['nightStart', 'nightEnd'] as const).map((k) => (
          <label key={k} className="flex flex-col text-xs text-slate-400">
            {k === 'nightStart' ? 'Night starts' : 'Night ends'}
            <input type="time" className={input} value={s[k].slice(0, 5)} onChange={(e) => setS({ ...s, [k]: e.target.value })} />
          </label>
        ))}
        {number('onThreshold', 'ON threshold', 0.05)}
        {number('offThreshold', 'OFF threshold', 0.05)}
        {number('arrivalThreshold', 'Arrival threshold', 0.05)}
        {number('confidenceThreshold', 'Min confidence', 0.05)}
        {number('cooldownSeconds', 'Cooldown (s)', 5)}
        {number('preArrivalTimeoutMinutes', 'Pre-arrival timeout (min)', 5)}
        <label className="flex flex-col text-xs text-slate-400">
          Pre-arrival horizon
          <select className={input} value={s.preArrivalMinutes} onChange={(e) => setS({ ...s, preArrivalMinutes: Number(e.target.value) })}>
            {[15, 30, 60, 120].map((m) => <option key={m} value={m}>{m} min</option>)}
          </select>
        </label>
      </div>
      <button type="submit" className={button}>Apply settings</button>
    </form>
  )
}

/** The one bridge from a simulated decision to hardware: an explicit click, then the device's real answer. */
export function SendToDevice({ picker, deviceId, decision, light }: {
  picker: ReactNode
  deviceId: string
  decision: Simulation['decision']
  light: Simulation['light']
}) {
  const [sent, setSent] = useState<{ device: string; command: Command } | null>(null)
  const [error, setError] = useState('')
  const value = decision.command ?? light.mode
  const open = sent != null && (sent.command.status === 'PENDING' || sent.command.status === 'SENT')

  useEffect(() => {
    if (!sent || !open) return
    const timer = setInterval(() => {
      predictiveApi.command(sent.device, sent.command.id).then((command) => setSent({ device: sent.device, command })).catch((e) => setError(message(e)))
    }, 1500)
    return () => clearInterval(timer)
  }, [sent, open])

  const send = () => {
    setError('')
    setSent(null)
    predictiveApi.sendToDevice(deviceId, value).then((command) => setSent({ device: deviceId, command })).catch((e) => setError(message(e)))
  }

  return (
    <section className="card space-y-2 p-4">
      <h2 className="text-sm font-semibold text-slate-100">Try this simulated decision on a real ESP32</h2>
      <p className="text-xs text-slate-400">
        Sends a real <code>set_pir_led {value}</code> command. The scenario stays simulated; only the command and its acknowledgement are physical.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        {picker}
        <button type="button" className={button} disabled={!deviceId} onClick={send}>Send "{value}" to the device</button>
      </div>
      {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
      {sent && (
        <p role="status" className="text-sm text-slate-300">
          Real command <b>{sent.command.value}</b>: <b>{sent.command.status}</b>
          {sent.command.detail && ` · ${sent.command.detail}`}
          {sent.command.status !== 'ACKNOWLEDGED' && ' · not yet acknowledged by the device'}
        </p>
      )}
    </section>
  )
}

export function EvaluationPanel() {
  const [data, setData] = useState<Evaluation | null>(null)
  const [state, setState] = useState('')
  const load = () => {
    setState('Evaluating…')
    predictiveApi.evaluation().then((e) => { setData(e); setState('') }).catch((e) => setState(message(e)))
  }
  const cells = (name: string, s: Score) => (
    <>
      <td className="py-1 pr-3">{name}</td>
      <td>{pct(s.precision)}</td><td>{pct(s.recall)}</td><td>{pct(s.f1)}</td><td>{s.auc?.toFixed(3) ?? '—'}</td><td>{s.brier.toFixed(3)}</td>
      <td className="whitespace-nowrap">{s.truePositive} / {s.falsePositive} / {s.falseNegative} / {s.trueNegative}</td>
    </>
  )
  return (
    <section className="card space-y-2 p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-sm font-semibold text-slate-100">Model evaluation (synthetic data)</h2>
        <button type="button" className={button} onClick={load}>Show evaluation</button>
        <span className="text-xs text-slate-400">{state}</span>
      </div>
      {data && (
        <>
          <p className="text-sm font-medium text-slate-100">{data.data}</p>
          <p className="text-xs text-slate-400">{data.method} {data.baselines}</p>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm text-slate-300">
              <thead>
                <tr className="text-xs text-slate-400"><th>Target</th><th>Predictor</th><th>Precision</th><th>Recall</th><th>F1</th><th>ROC-AUC</th><th>Brier</th><th>TP / FP / FN / TN</th></tr>
              </thead>
              <tbody>
                {data.results.flatMap((r) => [
                  <tr key={r.target} className="border-t border-slate-700"><td className="pr-3">{r.target}</td>{cells(r.chosen.toLowerCase().replaceAll('_', ' '), r.model)}</tr>,
                  <tr key={`${r.target}-base`} className="text-slate-400"><td />{cells('baseline', r.baseline)}</tr>,
                ])}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-slate-400">
            For occupancy, FP is a false &quot;occupied&quot; and FN a false &quot;empty&quot;. For arrivals, FP is a predicted return that did not happen.
          </p>
          <details className="text-sm text-slate-300">
            <summary className="cursor-pointer">Algorithm selection and probability calibration</summary>
            {data.results.map((r) => (
              <div key={r.target} className="mt-2">
                <p className="font-medium">{r.target}</p>
                <p className="text-xs">Validation log loss: {Object.entries(r.validationLogLoss).map(([k, v]) => `${k.toLowerCase().replaceAll('_', ' ')} ${v.toFixed(3)}`).join(' · ')}</p>
                <p className="text-xs">Predicted → observed: {r.calibration.map((c) => `${pct(c.meanPredicted)} → ${pct(c.observedRate)} (n=${c.n})`).join(' · ')}</p>
              </div>
            ))}
          </details>
          <p className="text-sm text-slate-300">
            Arrival time judged at 16:00 on {data.arrivalDaysScored} test days: mean error of the median estimate{' '}
            {data.arrivalMedianErrorMinutes?.toFixed(0) ?? '—'} min; actual return fell inside the window {pct(data.arrivalWindowCoverage)} of the time (a middle-half window is expected near 50%).
          </p>
        </>
      )}
    </section>
  )
}
