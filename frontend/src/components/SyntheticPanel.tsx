import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type SyntheticForecast, type SyntheticStatus } from '../api'
import { fmtCount, fmtDate, fmtDateTime, num, pct, verdict } from '../insights'

const SCENARIO_LABEL: Record<string, string> = {
  FROZEN_SENSOR: 'Frozen sensor',
  GAS_SATURATION: 'MQ-2 ADC saturated (4095)',
  MISSING_VALUES: 'Missing sensor values',
  DISCONNECTION: 'Device disconnected',
  UNUSUAL_MOTION: 'Unusual motion',
}

/**
 * A 404 here is not a "no data" answer. The frontend calls /api/devices/{id}/ml/synthetic, which only exists in backends
 * that include SyntheticController. A server built before that controller was added answers 404 for every such URL.
 */
function describeFailure(e: unknown, fallback: string): string {
  if (e instanceof ApiError && e.status === 404) {
    return 'The backend answered 404 for the synthetic endpoints. The running server was built before they existed, so rebuild and restart the backend (docker compose up -d --build backend).'
  }
  return e instanceof ApiError ? e.message : fallback
}

function SyntheticBadge() {
  return <span className="rounded bg-violet-500/25 px-1.5 py-0.5 text-[11px] font-semibold tracking-wide text-violet-200">SYNTHETIC</span>
}

/**
 * The demonstration pipeline on GENERATED data. Kept visually apart from the real panels (violet, labelled SYNTHETIC
 * everywhere) so its numbers are never mistaken for what the model learned about this home.
 */
export default function SyntheticPanel({ deviceId }: { deviceId: string }) {
  const [state, setState] = useState<{ forDevice: string; data?: SyntheticStatus; error?: string } | null>(null)
  const [forecast, setForecast] = useState<{ key: string; data?: SyntheticForecast } | null>(null)
  const [busy, setBusy] = useState<'' | 'generate' | 'train'>('')
  const [actionError, setActionError] = useState('')
  const current = state && state.forDevice === deviceId ? state : null
  const s = current?.data
  const modelKey = s?.model && s.model.state !== 'INSUFFICIENT_DATA' ? `${deviceId}:${s.model.version}` : ''
  const shownForecast = forecast && forecast.key === modelKey ? forecast.data : undefined

  const load = useCallback(() => {
    api
      .syntheticStatus(deviceId)
      .then((d) => setState({ forDevice: deviceId, data: d }))
      .catch((e) => setState({ forDevice: deviceId, error: describeFailure(e, 'Could not load the synthetic dataset') }))
  }, [deviceId])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    if (!modelKey) return
    let cancelled = false
    api.syntheticForecast(deviceId, 24).then((f) => !cancelled && setForecast({ key: modelKey, data: f })).catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [modelKey, deviceId])

  const run = async (what: 'generate' | 'train') => {
    setBusy(what)
    setActionError('')
    try {
      const next = what === 'generate' ? await api.generateSynthetic(deviceId) : await api.trainSynthetic(deviceId)
      setState({ forDevice: deviceId, data: next })
    } catch (e) {
      setActionError(describeFailure(e, 'Request failed'))
    } finally {
      setBusy('')
    }
  }

  const ds = s?.dataset
  const m = s?.model?.metrics
  const t = s?.training

  return (
    <section className="rounded-xl border border-violet-500/40 bg-slate-800 p-5 shadow-lg" aria-labelledby="synthetic-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="synthetic-heading" className="font-semibold">Synthetic demonstration model</h3>
        <SyntheticBadge />
      </div>
      <p className="mt-2 rounded bg-violet-500/10 p-2 text-xs text-violet-100" role="note">
        {s?.notice ?? 'Generated data, used only to exercise the ML pipeline. It is not your home\'s history and nothing here describes your routine.'}
      </p>

      {!current && <p className="mt-3 text-xs text-slate-400">Loading...</p>}
      {current?.error && <p className="mt-3 text-sm text-red-400">{current.error} <button onClick={load} className="underline">Try again</button></p>}

      {s && (
        <>
          {/* ---- dataset ---- */}
          <div className="mt-4 rounded-lg bg-slate-900 p-3">
            <h4 className="text-sm font-medium">Dataset <span className="font-normal text-slate-400">(source: synthetic)</span></h4>
            {ds ? (
              <>
                <dl className="mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
                  <div><dt className="inline text-slate-500">Generated records: </dt><dd className="inline tabular-nums">{fmtCount(ds.recordCount)}</dd></div>
                  <div><dt className="inline text-slate-500">Date range: </dt><dd className="inline">{fmtDate(ds.startsAt)} to {fmtDate(ds.endsAt)} ({ds.days} days, 1-minute readings)</dd></div>
                  <div><dt className="inline text-slate-500">Simulated offline: </dt><dd className="inline">{ds.offlineMinutes} min in {Math.max(0, Math.floor(ds.connectivityEvents / 2))} periods</dd></div>
                  <div><dt className="inline text-slate-500">Occupied / motion share: </dt><dd className="inline">{pct(ds.occupiedShare)} / {pct(ds.motionShare)} of minutes</dd></div>
                  <div><dt className="inline text-slate-500">Generator: </dt><dd className="inline">{ds.generatorVersion}, seed {ds.seed}</dd></div>
                  <div><dt className="inline text-slate-500">Created: </dt><dd className="inline">{fmtDateTime(ds.createdAt)}</dd></div>
                </dl>
                <p className="mt-2 text-[11px] text-slate-500">Stored in separate tables. Your device's real telemetry, real model and real anomalies are never mixed with it.</p>
              </>
            ) : (
              <p className="mt-1 text-xs text-slate-400">No synthetic dataset yet. Generating one creates 30 days of simulated minute-by-minute readings for this device, in separate tables. It does not run by itself.</p>
            )}
            <div className="mt-3 flex flex-wrap gap-2">
              {!ds && (
                <button type="button" onClick={() => run('generate')} disabled={busy !== ''}
                  className="rounded-md bg-violet-600 px-3 py-1.5 text-sm font-medium hover:bg-violet-500 disabled:opacity-60">
                  {busy === 'generate' ? 'Generating...' : 'Generate 30-day synthetic dataset'}
                </button>
              )}
              {ds && (
                <button type="button" onClick={() => run('train')} disabled={busy !== ''}
                  className="rounded-md bg-violet-600 px-3 py-1.5 text-sm font-medium hover:bg-violet-500 disabled:opacity-60">
                  {busy === 'train' ? 'Training...' : s.model ? 'Retrain on synthetic data' : 'Train on synthetic data'}
                </button>
              )}
            </div>
            {actionError && <p className="mt-2 text-sm text-red-400">{actionError}</p>}
          </div>

          {/* ---- model ---- */}
          {s.model && (
            <div className="mt-4">
              <h4 className="text-sm font-medium">Model <SyntheticBadge /> v{s.model.version} · {s.model.state === 'READY' ? 'ready' : s.model.state === 'LOW_QUALITY' ? 'low quality' : 'not enough data'}</h4>
              <p className="mt-1 text-xs text-slate-400">Trained {fmtDateTime(s.model.trainedAt)} · {s.model.timeZone}</p>
              {t && (
                <p className="mt-1 text-xs text-slate-400">
                  Chronological split: train {fmtCount(t.train.slots)} · validation {fmtCount(t.validation.slots)} · test {fmtCount(t.test.slots)} fifteen-minute slots,
                  {' '}{t.embargoMinutes}-minute gap between parts ({t.embargoDropped} slots dropped), {t.scenarioSlotsExcluded} anomaly-scenario slots kept out.
                  {' '}Regularisation {t.lambda} chosen on validation.
                </p>
              )}
              {m && (
                <>
                  <p className={`mt-2 text-xs ${verdict(m).better ? 'text-green-300' : 'text-red-300'}`}>{verdict(m).text}</p>
                  <div className="mt-2 overflow-x-auto">
                    <table className="w-full text-left text-xs">
                      <thead className="text-slate-500">
                        <tr><th className="py-1 pr-3 font-medium">Test-period measure</th><th className="pr-3 font-medium">Model</th><th className="font-medium">Majority-class baseline</th></tr>
                      </thead>
                      <tbody className="tabular-nums text-slate-200">
                        <tr><td className="py-1 pr-3">Accuracy</td><td className="pr-3">{pct(m.accuracy, 1)}</td><td>{pct(m.baselineAccuracy, 1)}</td></tr>
                        <tr><td className="py-1 pr-3">Precision / Recall</td><td className="pr-3">{m.precision === null ? 'n/a' : pct(m.precision, 1)} / {m.recall === null ? 'n/a' : pct(m.recall, 1)}</td><td>-</td></tr>
                        <tr><td className="py-1 pr-3">F1</td><td className="pr-3">{num(m.f1, 3)}</td><td>-</td></tr>
                        <tr><td className="py-1 pr-3">AUC</td><td className="pr-3">{num(m.auc, 3)}</td><td>0.5</td></tr>
                        <tr><td className="py-1 pr-3">Log loss</td><td className="pr-3">{num(m.logLoss, 3)}</td><td>{num(m.baselineLogLoss, 3)}</td></tr>
                      </tbody>
                    </table>
                  </div>
                  {t?.agreementWithTrueOccupancy && (
                    <p className="mt-2 text-[11px] text-slate-500">
                      The model forecasts PIR activity, like the real one. Against the generator's true occupancy it agrees on {pct(t.agreementWithTrueOccupancy.accuracy, 1)} of test slots
                      (always guessing the commonest state: {pct(t.agreementWithTrueOccupancy.majorityBaseline, 1)}): a sleeping household is occupied but quiet, so motion is only a partial signal.
                    </p>
                  )}
                </>
              )}
              {shownForecast && (
                <div className="mt-3">
                  <p className="text-xs text-slate-400">Synthetic forecast, next 24 hours ({shownForecast.timeZone}): chance of motion in the <em>simulated</em> household</p>
                  <div className="mt-1 flex h-16 items-end gap-0.5" role="img" aria-label="Synthetic model forecast for the next 24 hours">
                    {shownForecast.points.map((p) => (
                      <div key={p.at} className="flex-1 rounded-t-sm bg-violet-400/70" style={{ height: `${Math.max(3, p.probability * 100)}%` }}
                        title={`${p.dayOfWeek} ${String(p.hour).padStart(2, '0')}:00 · ${pct(p.probability)} (synthetic)`} />
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* ---- anomaly scenarios ---- */}
          {s.anomalies && (
            <div className="mt-4">
              <h4 className="text-sm font-medium">Anomaly scenarios <SyntheticBadge /></h4>
              <ul className="mt-2 space-y-1 text-xs">
                {s.anomalies.scenarios.map((a) => (
                  <li key={a.scenario} className="flex flex-wrap gap-x-2">
                    <span className={`w-20 font-medium ${a.detected ? 'text-green-300' : 'text-red-300'}`}>{a.detected ? 'detected' : 'missed'}</span>
                    <span className="text-slate-200">{SCENARIO_LABEL[a.scenario] ?? a.scenario}</span>
                    <span className="text-slate-500">{a.detected ? `after ${a.delayMinutes} min as ${a.detectedAs}` : `expected ${a.expectedRule}`}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-[11px] text-slate-500">
                False alarms in {num(s.anomalies.normalHours, 0)} hours of normal simulated operation: {s.anomalies.totalFalsePositiveEpisodes}
                {Object.keys(s.anomalies.falsePositiveEpisodes).length > 0 && ` (${Object.entries(s.anomalies.falsePositiveEpisodes).map(([k, v]) => `${k.toLowerCase().replace(/_/g, ' ')} ${v}`).join(', ')})`}.
                {' '}The existing rules were not tuned to this data.
              </p>
            </div>
          )}
        </>
      )}
    </section>
  )
}
