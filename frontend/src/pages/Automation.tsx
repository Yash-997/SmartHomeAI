import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device, type Execution, type Rule } from '../api'
import ExecutionList from '../components/ExecutionList'
import Shell, { PageHeader } from '../components/Shell'
import RuleBuilder from '../components/RuleBuilder'
import { RULE_TEMPLATES, describeAction, describeCondition, deviceName, formatDuration, type Draft } from '../automation'
import { ArrowRightIcon, BoltIcon, ClockIcon, PencilIcon, PlusIcon, TrashIcon } from '../ui/icons'

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
  const scheduled = rule.trigger.type === 'SCHEDULE'
  const conds = rule.conditions.map(describeCondition)
  const days = rule.trigger.days && rule.trigger.days.length < 7 ? rule.trigger.days.join(', ') : 'every day'

  return (
    <li className={`card p-5 transition-opacity ${rule.enabled ? '' : 'opacity-70'}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold">{rule.name}</h3>
          <p className="mt-0.5 text-xs text-slate-500">
            Priority {rule.priority} · cooldown {formatDuration(rule.cooldownSeconds)}
            {rule.lastExecutedAt ? ` · last ran ${new Date(rule.lastExecutedAt).toLocaleString()}` : ' · has not run yet'}
          </p>
        </div>
        <button type="button" role="switch" aria-checked={rule.enabled} aria-label={`${rule.name} enabled`} disabled={busy}
          onClick={() => run(() => api.setRuleEnabled(rule.id, !rule.enabled))}
          className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-60 ${rule.enabled ? 'bg-indigo-400' : 'bg-slate-600'}`}>
          <span aria-hidden="true" className={`absolute top-0.5 h-5 w-5 rounded-full shadow transition-all ${rule.enabled ? 'left-[22px] bg-slate-950' : 'left-0.5 bg-slate-300'}`} />
        </button>
      </div>

      <div className="mt-4 flex flex-col items-stretch gap-2 sm:flex-row sm:items-center">
        <div className="min-w-0 flex-1 rounded-xl bg-slate-900/70 p-3">
          <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            {scheduled ? <ClockIcon width={14} height={14} /> : <BoltIcon width={14} height={14} />} When
          </p>
          {scheduled && <p className="mt-1 text-sm font-medium">At {rule.trigger.time}, {days}</p>}
          {conds.map((c, i) => (
            <p key={i} className="mt-1 text-sm font-medium">{i > 0 && <span className="mr-1 text-xs font-normal text-slate-500">{rule.logic === 'AND' ? 'and' : 'or'}</span>}{c}</p>
          ))}
          {scheduled && conds.length > 0 && <p className="mt-1 text-xs text-slate-500">only if the above holds</p>}
          <p className="mt-1 text-xs text-slate-500">{scheduled ? rule.trigger.timeZone : `on ${deviceName(devices, rule.deviceId)}`}</p>
        </div>
        <ArrowRightIcon aria-hidden="true" className="mx-auto shrink-0 rotate-90 text-slate-500 sm:rotate-0" />
        <div className="min-w-0 flex-1 rounded-xl bg-slate-900/70 p-3">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">Then</p>
          <p className="mt-1 text-sm font-medium">{describeAction(rule.action.action, rule.action.value)}</p>
          <p className="mt-1 text-xs text-slate-500">on {deviceName(devices, rule.targetDeviceId)}{sameDevice ? '' : ' (another device)'}</p>
        </div>
      </div>

      {error && <p className="mt-3 text-sm text-red-400" role="alert">{error}</p>}

      {!confirming ? (
        <div className="mt-4 flex flex-wrap gap-2">
          <button type="button" onClick={onEdit} className="btn-secondary inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"><PencilIcon width={15} height={15} />Edit</button>
          <button type="button" onClick={toggleHistory} aria-expanded={!!history} className="btn-secondary rounded-lg px-3 py-1.5 text-sm">
            {history ? 'Hide history' : 'History'}
          </button>
          <button type="button" onClick={() => setConfirming(true)} className="btn-danger ml-auto inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"><TrashIcon width={15} height={15} />Delete</button>
        </div>
      ) : (
        <div role="alertdialog" aria-label={`Delete ${rule.name}`} className="mt-4 rounded-xl border border-red-400/30 bg-red-950/40 p-3 text-sm">
          <p>Delete <strong>{rule.name}</strong> and its history? This cannot be undone.</p>
          <div className="mt-3 flex gap-2">
            <button type="button" disabled={busy} onClick={() => run(() => api.deleteRule(rule.id))} className="rounded-lg bg-red-500 px-3 py-1.5 font-medium text-slate-950 hover:bg-red-400 disabled:opacity-60">Yes, delete</button>
            <button type="button" onClick={() => setConfirming(false)} className="btn-secondary rounded-lg px-3 py-1.5">Keep</button>
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
  const [template, setTemplate] = useState<Partial<Draft> | undefined>(undefined)
  const startNew = (t?: Partial<Draft>) => {
    setTemplate(t)
    setEditing('new')
  }

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
    <Shell>
      <PageHeader
        title="Automation"
        subtitle="Rules that control your devices from sensor readings or a schedule."
        actions={
          <button type="button" onClick={() => startNew()} disabled={!ready || ready.devices.length === 0} className="btn-primary inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm">
            <PlusIcon width={16} height={16} />New rule
          </button>
        }
      />
        {load.kind === 'loading' && <p role="status" className="text-slate-400">Loading...</p>}
        {load.kind === 'error' && (
          <div role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300">
            {load.message}{' '}
            <button onClick={refresh} className="underline">Try again</button>
          </div>
        )}

        {ready && ready.devices.length === 0 && (
          <p className="card p-8 text-center text-sm text-slate-400">
            You need a paired device before you can create rules. Add one on the Devices page.
          </p>
        )}
        {ready && ready.devices.length > 0 && ready.rules.length === 0 && (
          <section className="card overflow-hidden p-6 sm:p-10" aria-labelledby="empty-rules">
            <div className="mx-auto max-w-xl text-center">
              <span aria-hidden="true" className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-indigo-400/15 text-indigo-400"><BoltIcon width={28} height={28} /></span>
              <h2 id="empty-rules" className="mt-4 text-xl font-semibold">Let your home act on its own</h2>
              <p className="mt-2 text-sm text-slate-400">A rule watches a sensor or the clock and sets an output for you. Pick a starting point below or build one from scratch.</p>
              <button type="button" onClick={() => startNew()} className="btn-primary mt-5 inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm"><PlusIcon width={16} height={16} />Create your first rule</button>
            </div>
          </section>
        )}

        {ready && ready.rules.length > 0 && (
          <section aria-labelledby="rules-heading" className="space-y-3">
            <h2 id="rules-heading" className="text-base font-semibold">Your rules <span className="ml-1 text-sm font-normal text-slate-500">{ready.rules.length}</span></h2>
            <ul className="grid gap-4 xl:grid-cols-2">
              {ready.rules.map((r) => (
                <RuleCard key={r.id} rule={r} devices={ready.devices} onEdit={() => setEditing(r)} onChanged={refresh} />
              ))}
            </ul>
          </section>
        )}

        {ready && ready.devices.length > 0 && (
          <section aria-labelledby="templates-heading" className="space-y-3">
            <div>
              <h2 id="templates-heading" className="text-base font-semibold">{ready.rules.length === 0 ? 'Start from an example' : 'Quick starts'}</h2>
              <p className="text-sm text-slate-400">Opens the rule form prefilled. Nothing is saved until you press Create rule.</p>
            </div>
            <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {RULE_TEMPLATES.map((t) => (
                <li key={t.id}>
                  <button type="button" onClick={() => startNew(t.draft)}
                    className="flex h-full w-full flex-col rounded-2xl bg-slate-800 p-4 text-left shadow-sm transition hover:-translate-y-0.5 hover:bg-slate-700 focus-visible:-translate-y-0.5">
                    <span className="font-medium">{t.title}</span>
                    <span className="mt-1 text-xs text-slate-400">{t.blurb}</span>
                    <span className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-300">
                      <span className="rounded-md bg-slate-900/70 px-2 py-1">{t.when}</span>
                      <ArrowRightIcon width={14} height={14} className="text-slate-500" />
                      <span className="rounded-md bg-slate-900/70 px-2 py-1">{t.then}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
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

      {editing && ready && (
        <RuleBuilder
          devices={ready.devices}
          rule={editing === 'new' ? null : editing}
          initial={editing === 'new' ? template : undefined}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            refresh()
          }}
        />
      )}
    </Shell>
  )
}
