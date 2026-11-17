import { AnimatePresence, m } from 'motion/react'
import type { Execution, ExecutionOutcome } from '../api'
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

/** A small glyph per outcome family, so the timeline can be scanned without reading every label. */
function OutcomeIcon({ outcome }: { outcome: ExecutionOutcome }) {
  const path =
    outcome === 'EXECUTED' ? 'M5 12.5l4.5 4.5L19 7.5'
    : outcome === 'FAILED' ? 'M6 6l12 12M18 6 6 18'
    : outcome === 'SKIPPED_COOLDOWN' ? 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z'
    : outcome === 'SKIPPED_NO_DATA' || outcome === 'SKIPPED_STALE_DATA' ? 'M4 20 20 4M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z'
    : 'M8 12h8'
  return (
    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={path} />
    </svg>
  )
}

/** Audit trail: when, which rule, what happened and why, with the sensor values seen. New entries slide in at the top. */
export default function ExecutionList({ items, showRule }: { items: Execution[]; showRule: boolean }) {
  return (
    <ul className="relative space-y-2 before:absolute before:bottom-3 before:left-[15px] before:top-3 before:w-px before:bg-slate-700">
      <AnimatePresence initial={false}>
        {items.map((e) => (
          <m.li
            key={e.id}
            layout="position"
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.25, ease: [0.2, 0.7, 0.2, 1] }}
            className="relative flex gap-3 text-sm"
          >
            <span className={`relative z-10 mt-2 grid h-[30px] w-[30px] shrink-0 place-items-center rounded-full ring-4 ring-slate-800 ${OUTCOME[e.outcome].tone}`}>
              <OutcomeIcon outcome={e.outcome} />
            </span>
            <div className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-900/70 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${OUTCOME[e.outcome].tone}`}>{OUTCOME[e.outcome].label}</span>
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
            </div>
          </m.li>
        ))}
      </AnimatePresence>
    </ul>
  )
}
