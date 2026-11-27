import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, type Device } from '../api'
import PredictiveCharts from '../components/PredictiveCharts'
import { EvaluationPanel, SettingsForm, SendToDevice } from '../components/PredictivePanels'
import Shell, { PageHeader } from '../components/Shell'
import { hhmm, pct, predictiveApi, toMinute, type Live, type ScenarioId, type Simulation } from '../predictive'

const SCENARIOS: { id: ScenarioId; label: string; hint: string }[] = [
  { id: 'NORMAL_WEEKDAY', label: '1. Normal weekday', hint: 'Compare the learned routine with the generated history, then press Start.' },
  { id: 'PREDICTIVE_ARRIVAL', label: '2. Predictive arrival', hint: 'It is 18:30. Read the 60-minute return probability and the decision.' },
  { id: 'NIGHT_MODE', label: '3. Night Mode', hint: 'It is 02:00. Press Trigger PIR motion.' },
  { id: 'UNUSUAL_WAKE', label: '4. Unusual wake-up', hint: 'Today the resident sleeps late. Check the deviation panel, then press Start.' },
  { id: 'DELAYED_RETURN', label: '5. Delayed return', hint: 'Return is one hour late today. Press Start and watch the window and probabilities move.' },
  { id: 'MANUAL_OVERRIDE', label: '6. Manual override', hint: 'It is 02:00. Trigger PIR motion, then switch the LED ON.' },
  { id: 'MISSED_ROUTINE', label: '7. Missed routine', hint: 'The resident stays home today. Press Start and compare with a normal working day.' },
]

const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Request failed')
const input = 'field-input rounded-lg px-3 py-2 text-sm'
const button = 'btn-secondary rounded-lg px-3 py-2 text-sm font-medium'
const primary = 'btn-primary rounded-lg px-3 py-2 text-sm'

function Stat({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="card p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-400">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-slate-100">{value}</p>
      {note && <p className="mt-1 text-xs text-slate-400">{note}</p>}
    </div>
  )
}

function Predictions({ v }: { v: Simulation | Live }) {
  const p = v.presence
  const target = v.arrivalApplicable ? v.arrival?.horizons.find((h) => h.minutes === v.settings.preArrivalMinutes) : undefined
  const window = v.arrivalApplicable && v.arrival?.windowFrom != null ? `${hhmm(v.arrival.windowFrom)} – ${hhmm(v.arrival.windowTo)}` : '—'
  return (
    <>
      <section aria-label="Prediction" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={v.mode === 'SIMULATION' ? 'Simulation time' : 'Current time'} value={v.now.slice(11, 16)} note={new Date(v.now).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' })} />
        <Stat label="Occupancy probability" value={pct(p?.probability)} note={p ? `Confidence ${pct(p.confidence)}` : 'No prediction'} />
        <Stat label="Predicted state" value={p ? p.state.replace('_', ' ') : 'UNAVAILABLE'} note={`Predicted at ${v.now.slice(11, 16)}`} />
        <Stat label="Night Mode" value={v.nightActive ? 'ACTIVE' : v.settings.nightEnabled ? 'Inactive' : 'Disabled'} note={`${v.settings.nightStart.slice(0, 5)} to ${v.settings.nightEnd.slice(0, 5)}`} />
        <Stat label="Expected arrival window" value={window} note={v.arrivalApplicable ? `Middle half of ${v.arrival?.supportDays ?? 0} later returns in ${v.arrival?.comparableDays ?? 0} comparable days` : 'Not applicable: not predicted away'} />
        <Stat label={`Return within ${v.settings.preArrivalMinutes} min`} value={pct(target?.probability)} />
        <Stat label="Arrival model confidence" value={pct(target?.confidence)} note="Decisiveness × historical support" />
        <Stat label="Light" value={v.light.on ? 'ON' : 'OFF'} note={`Mode: ${v.light.mode}${v.override === 'NONE' ? '' : ` · manual ${v.override}`}`} />
      </section>

      {v.arrivalApplicable && v.arrival && (
        <section className="card overflow-x-auto p-4">
          <h2 className="text-sm font-semibold text-slate-100">Return probability by horizon</h2>
          <table className="mt-2 w-full text-left text-sm text-slate-300">
            <thead><tr className="text-xs text-slate-400"><th>Within</th><th>Probability</th><th>Confidence</th></tr></thead>
            <tbody>
              {v.arrival.horizons.map((h) => (
                <tr key={h.minutes}><td className="py-1">{h.minutes} min</td><td>{pct(h.probability)}</td><td>{pct(h.confidence)}</td></tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="card p-4" aria-live="polite">
        <h2 className="text-sm font-semibold text-slate-100">Decision engine: {v.decision.type.replaceAll('_', ' ')}</h2>
        <p className="mt-1 text-sm text-slate-300">{v.decision.reason}</p>
        <p className="mt-2 text-xs text-slate-400">
          Command: {v.decision.command ?? 'none'} · The model only predicts; this engine decides.
          {v.lastTrainedAt && ` · Model v${v.modelVersion} last trained ${new Date(v.lastTrainedAt).toLocaleString()}`}
          {v.preArrival.since && ` · Waiting for arrival since ${v.preArrival.since.slice(11, 16)} (times out after ${v.settings.preArrivalTimeoutMinutes} min)`}
          {v.preArrival.expired && ' · Pre-arrival lighting timed out'}
        </p>
      </section>
    </>
  )
}

export default function Predictive() {
  const [mode, setMode] = useState<'SIMULATION' | 'LIVE'>('SIMULATION')
  const [sim, setSim] = useState<Simulation | null>(null)
  const [live, setLive] = useState<Live | null>(null)
  const [devices, setDevices] = useState<Device[]>([])
  const [deviceId, setDeviceId] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const busy = useRef(false)

  const run = useCallback((request: Promise<Simulation>) => {
    busy.current = true
    return request
      .then((v) => { setSim(v); setError('') })
      .catch((e) => { setError(message(e)); setRunning(false) })
      .finally(() => { busy.current = false })
  }, [])
  const act = (action: string, fields?: Record<string, unknown>) => run(predictiveApi.act(action, fields))

  useEffect(() => {
    run(predictiveApi.simulation())
    api.listDevices().then((list) => {
      setDevices(list)
      setDeviceId((cur) => cur || (list.find((d) => d.connection === 'ONLINE') ?? list[0])?.id || '')
    }).catch((e) => setError(message(e)))
  }, [run])

  useEffect(() => {
    if (!running || mode !== 'SIMULATION') return
    const timer = setInterval(() => {
      if (!busy.current) run(predictiveApi.act('advance', { minutes: 5 }))
    }, 1000)
    return () => clearInterval(timer)
  }, [running, mode, run])

  useEffect(() => {
    if (mode !== 'LIVE' || !deviceId) return
    let stale = false
    const load = () => predictiveApi.live(deviceId)
      .then((v) => { if (!stale) { setLive(v); setError('') } })
      .catch((e) => { if (!stale) setError(message(e)) })
    load()
    const timer = setInterval(load, 3000)
    return () => { stale = true; clearInterval(timer); setLive(null) }
  }, [mode, deviceId])

  const configure = (body: Parameters<typeof predictiveApi.configureLive>[1]) =>
    predictiveApi.configureLive(deviceId, body).then((v) => { setLive(v); setError('') }).catch((e) => setError(message(e)))

  const picker = (
    <label className="flex items-center gap-2 text-sm text-slate-300">
      Device
      <select className={input} value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
        {devices.length === 0 && <option value="">No devices</option>}
        {devices.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.connection})</option>)}
      </select>
    </label>
  )

  return (
    <Shell wide>
      <PageHeader
        title="Predictive Occupancy Demonstration"
        subtitle="Learned routine, occupancy and arrival prediction, and context-aware lighting."
        actions={
          <div className="flex gap-2" role="group" aria-label="Mode">
            <button type="button" className={mode === 'SIMULATION' ? primary : button} aria-pressed={mode === 'SIMULATION'} onClick={() => setMode('SIMULATION')}>Simulation</button>
            <button type="button" className={mode === 'LIVE' ? primary : button} aria-pressed={mode === 'LIVE'} onClick={() => { setRunning(false); setMode('LIVE') }}>Live ESP32</button>
          </div>
        }
      />

      <div role="status" className="card border-2 p-4">
        <p className="font-semibold text-slate-100">{mode === 'SIMULATION' ? 'SIMULATION MODE' : 'LIVE ESP32 MODE'}</p>
        <p className="text-sm text-slate-300">
          {mode === 'SIMULATION' ? sim && `${sim.dataLabel}. ${sim.acknowledgement}` : live?.dataLabel}
        </p>
      </div>

      {error && <p role="alert" className="card p-3 text-sm text-red-400">{error}</p>}

      {mode === 'SIMULATION' && sim && (
        <>
          <section className="card space-y-3 p-4" aria-label="Simulation controls">
            <div className="flex flex-wrap items-center gap-2">
              <label className="flex items-center gap-2 text-sm text-slate-300">
                Scenario
                <select className={input} value={sim.scenario} onChange={(e) => { setRunning(false); act('scenario', { scenario: e.target.value }) }}>
                  {SCENARIOS.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
                </select>
              </label>
              <button type="button" className={primary} onClick={() => setRunning((r) => !r)}>{running ? 'Pause' : 'Start'}</button>
              <button type="button" className={button} onClick={() => { setRunning(false); act('reset') }}>Reset</button>
              <label className="flex items-center gap-2 text-sm text-slate-300">
                Time
                <input type="time" className={input} value={hhmm(sim.minute)} onChange={(e) => e.target.value && act('time', { minute: toMinute(e.target.value) })} />
              </label>
              <button type="button" className={button} onClick={() => act('advance', { minutes: 15 })}>+15 min</button>
              <button type="button" className={primary} onClick={() => act('motion')}>Trigger PIR motion</button>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-sm text-slate-300">
              LED
              {(['ON', 'OFF', 'NONE'] as const).map((o) => (
                <button key={o} type="button" className={sim.override === o ? primary : button} aria-pressed={sim.override === o} onClick={() => act('override', { override: o })}>
                  {o === 'NONE' ? 'Automatic' : `Manual ${o}`}
                </button>
              ))}
            </div>
            <p className="text-xs text-slate-400">{SCENARIOS.find((s) => s.id === sim.scenario)?.hint} Start advances 5 simulated minutes per second.</p>
          </section>

          <Predictions v={sim} />

          <section className="grid gap-3 lg:grid-cols-2">
            <div className="card p-4">
              <h2 className="text-sm font-semibold text-slate-100">Learned weekday routine (from {sim.learned.days} days of motion, recent days weighted more)</h2>
              <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm text-slate-300">
                <dt>Wake-up</dt><dd>about {hhmm(sim.learned.wake)}</dd>
                <dt>Departure</dt><dd>about {hhmm(sim.learned.leave)}</dd>
                <dt>Return window</dt><dd>{hhmm(sim.learned.returnFrom)} – {hhmm(sim.learned.returnTo)}</dd>
                <dt>History</dt><dd>{sim.dataset.days} synthetic days, seed {sim.dataset.seed}</dd>
                <dt>Weekends</dt><dd>wake about {hhmm(sim.learnedWeekend.wake)}</dd>
              </dl>
              <h2 className="mt-4 text-sm font-semibold text-slate-100">Deviation today: {sim.deviation.kind.replaceAll('_', ' ')}</h2>
              <p className="text-sm text-slate-300">{sim.deviation.explanation}</p>
            </div>
            <RoutineForm key={`${JSON.stringify(sim.today)}${sim.sustainDays}`} sim={sim} onApply={(f) => { setRunning(false); act('today', f) }} />
          </section>

          <PredictiveCharts sim={sim} />

          <SettingsForm key={JSON.stringify(sim.settings)} settings={sim.settings} onApply={(settings) => act('settings', { settings })} />

          <SendToDevice picker={picker} deviceId={deviceId} decision={sim.decision} light={sim.light} />

          <section className="card p-4">
            <h2 className="text-sm font-semibold text-slate-100">Decision log (simulated)</h2>
            <ul className="mt-2 max-h-64 space-y-1 overflow-y-auto text-sm text-slate-300">
              {sim.log.map((l, i) => <li key={i}><b>{hhmm(l.minute)}</b> {l.type.replaceAll('_', ' ')}{l.command && ` (${l.command})`}: {l.reason}</li>)}
            </ul>
          </section>

          <EvaluationPanel key={sim.modelVersion} />
        </>
      )}

      {mode === 'LIVE' && (
        <>
          <section className="card flex flex-wrap items-center gap-2 p-4" aria-label="Live controls">
            {picker}
            {live && (
              <>
                <button type="button" className={live.armed ? 'btn-danger rounded-lg px-3 py-2 text-sm' : primary} onClick={() => configure({ armed: !live.armed })}>
                  {live.armed ? 'Stop controlling the LED' : 'Start controlling the real LED'}
                </button>
                {(['ON', 'OFF', 'NONE'] as const).map((o) => (
                  <button key={o} type="button" disabled={!live.armed} className={live.override === o ? primary : button} aria-pressed={live.override === o} onClick={() => configure({ override: o })}>
                    {o === 'NONE' ? 'Automatic' : `Manual ${o}`}
                  </button>
                ))}
              </>
            )}
          </section>
          {!live && deviceId && !error && <p className="text-sm text-slate-400">Loading live data…</p>}
          {live && (
            <>
              <p className="card p-4 text-sm text-slate-300">
                {live.armed ? 'ARMED: commands are sent to the device.' : 'Preview only: no commands are sent.'} Device {live.connection}. PIR data{' '}
                {live.sensorFresh ? 'fresh' : 'STALE or missing'}{live.sensorAgeSeconds != null && ` (${live.sensorAgeSeconds}s old)`}. {live.motionReadingsToday} readings today
                ({live.timeZone}). Last motion: {live.minutesSinceMotion == null ? 'none in the last 12 h of today' : `${live.minutesSinceMotion} min ago`}.
                {!live.deviceStateReported && ' The device has not reported its LED state yet.'}
              </p>
              <Predictions v={live} />
              {live.events.length > 0 && (
                <section className="card p-4">
                  <h2 className="text-sm font-semibold text-slate-100">Decision history while armed</h2>
                  <ul className="mt-2 max-h-48 space-y-1 overflow-y-auto text-sm text-slate-300">
                    {live.events.map((ev, i) => <li key={i}><b>{ev.at.slice(11, 19)}</b> {ev.type.replaceAll('_', ' ')}: {ev.reason}</li>)}
                  </ul>
                </section>
              )}
              <section className="card p-4">
                <h2 className="text-sm font-semibold text-slate-100">Device commands and acknowledgements (from the real command pipeline)</h2>
                {live.sendError && <p className="mt-1 text-sm text-red-400">{live.sendError}</p>}
                {live.commands.length === 0 ? <p className="mt-1 text-sm text-slate-400">No predictive commands have been sent to this device.</p> : (
                  <ul className="mt-2 space-y-1 text-sm text-slate-300">
                    {live.commands.map((c) => (
                      <li key={c.id}><b>{c.status}</b> · {c.action} {c.value} · sent {new Date(c.createdAt).toLocaleTimeString()}{c.completedAt && ` · answered ${new Date(c.completedAt).toLocaleTimeString()}`}{c.detail && ` · ${c.detail}`} ({c.origin})</li>
                    ))}
                  </ul>
                )}
              </section>
              <SettingsForm key={JSON.stringify(live.settings)} settings={live.settings} onApply={(settings) => configure({ settings })} />
            </>
          )}
        </>
      )}
    </Shell>
  )
}

function RoutineForm({ sim, onApply }: { sim: Simulation; onApply: (fields: Record<string, number>) => void }) {
  const stayed = sim.today.leave < 0
  const [t, setT] = useState({ wake: hhmm(sim.today.wake), leave: hhmm(stayed ? 570 : sim.today.leave), back: hhmm(sim.today.back) })
  const [sustain, setSustain] = useState(sim.sustainDays)
  const times = () => ({ wake: toMinute(t.wake), leave: toMinute(t.leave), back: toMinute(t.back), sustainDays: sustain })
  return (
    <div className="card space-y-3 p-4">
      <h2 className="text-sm font-semibold text-slate-100">Change today&apos;s behaviour</h2>
      <p className="text-xs text-slate-400">These times generate the simulated motion. The model never reads them.{stayed && ' Today the resident stays home.'}</p>
      <div className="flex flex-wrap gap-3">
        {(['wake', 'leave', 'back'] as const).map((k) => (
          <label key={k} className="flex flex-col text-xs text-slate-400">
            {{ wake: 'Wake-up', leave: 'Leaves', back: 'Returns' }[k]}
            <input type="time" className={input} value={t[k]} onChange={(e) => setT({ ...t, [k]: e.target.value })} />
          </label>
        ))}
        <label className="flex flex-col text-xs text-slate-400">
          Previous weekdays same as today
          <input type="number" min={0} max={15} className={`${input} w-24`} value={sustain} onChange={(e) => setSustain(Number(e.target.value))} />
        </label>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" className={primary} onClick={() => onApply(times())}>Apply to today</button>
        <button type="button" className={button} onClick={() => onApply({ ...times(), back: Math.min(22 * 60, toMinute(t.back) + 60) })}>Delay arrival by 1 hour</button>
        <button type="button" className={button} onClick={() => onApply({ ...times(), leave: -1 })}>Missed routine (stays home)</button>
      </div>
    </div>
  )
}
