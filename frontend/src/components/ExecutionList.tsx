import type { Execution } from '../api'
import { ACTION_LABEL, OUTCOME } from '../automation'

const COMMAND_TONE: Record<string, string> = {
  ACKNOWLEDGED: 'text-green-300',
  FAILED: 'text-red-300',
  EXPIRED: 'text-amber-300',
  UNKNOWN: 'text-amber-300',
}

function values(e: Execution): string {
  if (!e.triggerValues) return ''
  const t = e.triggerValues
  const part = (label: string, v: unknown, unit = '') => `${label} ${v === null || v === undefined ? 'no reading' : String(v) + unit}`
  return [part('temp', t.temperature, ' °C'), part('humidity', t.humidity, ' %'), part('motion', t.motion), part('gas', t.gas)].join(' · ')
}

/** Audit trail: when, which rule, what happened and why, with the sensor values seen. */
export default function ExecutionList({ items, showRule }: { items: Execution[]; showRule: boolean }) {
  return (
    <ul className="space-y-2">
      {items.map((e) => (
        <li key={e.id} className="rounded-md bg-slate-900 p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded px-2 py-0.5 text-xs font-medium ${OUTCOME[e.outcome].tone}`}>{OUTCOME[e.outcome].label}</span>
            {showRule && <span className="font-medium">{e.ruleName}</span>}
            <span className="text-slate-400">
              {ACTION_LABEL[e.action] ?? e.action} → {e.value}
            </span>
            {e.repeatCount > 1 && <span className="text-xs text-slate-500">×{e.repeatCount}</span>}
            <span className="ml-auto text-xs text-slate-500" title={new Date(e.createdAt).toLocaleString()}>
              {new Date(e.lastAt).toLocaleString()}
            </span>
          </div>
          {e.reason && <p className="mt-1 text-slate-300">{e.reason}</p>}
          {e.commandStatus && (
            <p className={`mt-1 text-xs ${COMMAND_TONE[e.commandStatus] ?? 'text-slate-400'}`}>Command: {e.commandStatus.toLowerCase()}</p>
          )}
          {e.triggerValues && <p className="mt-1 text-xs text-slate-500">Saw: {values(e)}</p>}
        </li>
      ))}
    </ul>
  )
}
