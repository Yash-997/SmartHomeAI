import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device, type Execution, type Rule } from '../api'
import ExecutionList from '../components/ExecutionList'
import Header from '../components/Header'
import RuleBuilder from '../components/RuleBuilder'
import { describeAction, describeWhen, deviceName, formatDuration } from '../automation'

type Load =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; rules: Rule[]; devices: Device[]; recent: Execution[] }

const POLL_MS = 10000

function RuleCard({ rule, devices, onEdit, onChanged }: { rule: Rule; devices: Device[]; onEdit: () => void; onChanged: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [history, setHistory] = useState<Execution[] | null>(null)
  const [historyError, setHistoryError] = useState('')

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true)
    setError('')
    try {
      await fn()
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const toggleHistory = async () => {
    if (history) return setHistory(null)
    setHistoryError('')
    try {
      setHistory(await api.ruleHistory(rule.id))
    } catch (e) {
      setHistoryError(e instanceof ApiError ? e.message : 'Could not load history')
    }
  }

  const sameDevice = rule.deviceId === rule.targetDeviceId

  return (
    <li className={`rounded-xl bg-slate-800 p-4 shadow-lg ${rule.enabled ? '' : 'opacity-70'}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate font-semibold">{rule.name}</h3>
          <p className="mt-1 text-sm text-slate-300">
            {describeWhen(rule)} <span className="text-slate-500">on {deviceName(devices, rule.deviceId)}</span>
          </p>
          <p className="text-sm text-slate-300">
            then {describeAction(rule.action.action, rule.action.value)}
            {!sameDevice && <span className="text-slate-500"> on {deviceName(devices, rule.targetDeviceId)}</span>}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Priority {rule.priority} · cooldown {formatDuration(rule.cooldownSeconds)}
            {rule.lastExecutedAt ? ` · last ran ${new Date(rule.lastExecutedAt).toLocaleString()}` : ' · has not run yet'}
          </p>
        </div>
        <button type="button" role="switch" aria-checked={rule.enabled} disabled={busy}
          onClick={() => run(() => api.setRuleEnabled(rule.id, !rule.enabled))}
          className={`rounded-full px-3 py-1 text-xs font-medium ${rule.enabled ? 'bg-green-600/80 hover:bg-green-600' : 'bg-slate-600 hover:bg-slate-500'}`}>
          {rule.enabled ? 'Enabled' : 'Disabled'}
        </button>
      </div>

      {error && <p className="mt-2 text-sm text-red-400">{error}</p>}

      {!confirming ? (
        <div className="mt-3 flex flex-wrap gap-2">
          <button onClick={onEdit} className="rounded-md bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600">Edit</button>
          <button onClick={toggleHistory} className="rounded-md bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600">
            {history ? 'Hide history' : 'History'}
          </button>
          <button onClick={() => setConfirming(true)} className="rounded-md bg-red-900/60 px-3 py-1 text-sm text-red-200 hover:bg-red-900">Delete</button>
        </div>
      ) : (
        <div className="mt-3 rounded-md bg-red-950/50 p-3 text-sm">
          <p>Delete <strong>{rule.name}</strong> and its history? This cannot be undone.</p>
          <div className="mt-3 flex gap-2">
            <button disabled={busy} onClick={() => run(() => api.deleteRule(rule.id))} className="rounded-md bg-red-600 px-3 py-1 hover:bg-red-500 disabled:opacity-60">Yes, delete</button>
            <button onClick={() => setConfirming(false)} className="rounded-md bg-slate-700 px-3 py-1 hover:bg-slate-600">Keep</button>
          </div>
        </div>
      )}

      {historyError && <p className="mt-2 text-sm text-red-400">{historyError}</p>}
      {history && (
        <div className="mt-3">
          {history.length === 0 ? <p className="text-sm text-slate-400">This rule has not matched or been held back yet.</p> : <ExecutionList items={history} showRule={false} />}
        </div>
      )}
    </li>
  )
}

export default function Automation() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [editing, setEditing] = useState<Rule | 'new' | null>(null)

  const refresh = useCallback(() => {
    Promise.all([api.listRules(), api.listDevices(), api.allHistory(30)])
      .then(([rules, devices, recent]) => setLoad({ kind: 'ready', rules, devices, recent }))
      // 401 is handled globally. A failed refresh keeps showing what we already had.
      .catch((e) =>
        setLoad((prev) => (prev.kind === 'ready' ? prev : { kind: 'error', message: e instanceof ApiError ? e.message : 'Failed to load automations' })),
      )
  }, [])

  useEffect(() => {
    refresh()
    const poll = setInterval(refresh, POLL_MS)
    return () => clearInterval(poll)
  }, [refresh])

  const ready = load.kind === 'ready' ? load : null

  return (
    <main className="min-h-screen bg-slate-900 p-6 text-slate-100">
      <div className="mx-auto max-w-3xl space-y-6">
        <Header />

        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold">Automation</h2>
            <p className="text-sm text-slate-400">Rules that control your devices from sensor readings or a schedule.</p>
          </div>
          <button onClick={() => setEditing('new')} disabled={!ready || ready.devices.length === 0}
            className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-50">
            New rule
          </button>
        </div>

        {load.kind === 'loading' && <p className="text-slate-400">Loading...</p>}
        {load.kind === 'error' && (
          <div className="rounded bg-red-500/10 p-3 text-sm text-red-400">
            {load.message}{' '}
            <button onClick={refresh} className="underline">Try again</button>
          </div>
        )}

        {ready && ready.devices.length === 0 && (
          <p className="rounded-xl bg-slate-800 p-6 text-center text-sm text-slate-400">
            You need a paired device before you can create rules. Add one on the Devices page.
          </p>
        )}
        {ready && ready.devices.length > 0 && ready.rules.length === 0 && (
          <p className="rounded-xl bg-slate-800 p-6 text-center text-sm text-slate-400">
            No rules yet. Create one, for example: when temperature is above 30, turn the PIR LED on.
          </p>
        )}

        {ready && ready.rules.length > 0 && (
          <ul className="space-y-3">
            {ready.rules.map((r) => (
              <RuleCard key={r.id} rule={r} devices={ready.devices} onEdit={() => setEditing(r)} onChanged={refresh} />
            ))}
          </ul>
        )}

        {ready && ready.rules.length > 0 && (
          <section className="space-y-2">
            <h3 className="text-sm font-semibold text-slate-300">Recent activity</h3>
            {ready.recent.length === 0 ? (
              <p className="text-sm text-slate-400">Nothing has happened yet. Entries appear when a rule matches or is held back (offline device, cooldown, missing data...).</p>
            ) : (
              <ExecutionList items={ready.recent} showRule />
            )}
          </section>
        )}
      </div>

      {editing && ready && (
        <RuleBuilder
          devices={ready.devices}
          rule={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            refresh()
          }}
        />
      )}
    </main>
  )
}
