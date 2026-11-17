import { useState } from 'react'
import { Link } from 'react-router-dom'
import { notify } from '../ui/toastStore'
import { api, ApiError, type AnswerStatus, type ChatMessage, type ChatSource, type ChatSuggestion, type PendingDeviceAction } from '../api'
import Markdown from '../ui/Markdown'
import { AlertIcon, ReportIcon } from '../ui/icons'
import { LogoMark } from '../ui/Logo'
import VisualView from './VisualView'

const STATUS_NOTE: Record<AnswerStatus, { label: string; tone: string } | null> = {
  ANSWERED: null,
  NO_CONTEXT: { label: 'Nothing found', tone: 'bg-slate-600 text-slate-100' },
  NOT_CONFIGURED: { label: 'No AI model set up: references only', tone: 'bg-amber-500/20 text-amber-300' },
  LLM_UNAVAILABLE: { label: 'AI model unavailable: references only', tone: 'bg-amber-500/20 text-amber-300' },
  RETRIEVAL_UNAVAILABLE: { label: 'Search unavailable', tone: 'bg-red-500/20 text-red-300' },
}

const TYPE_LABEL: Record<string, string> = {
  DEVICE_STATUS: 'Device status',
  SENSOR_HISTORY: 'Sensor history',
  ANOMALY: 'Anomaly',
  AUTOMATION: 'Automation',
  ML_MODEL: 'ML model',
  LIVE_DATA: 'Database query',
  DOCUMENT: 'Your document',
  KNOWLEDGE: 'Help text',
}

function SourceList({ sources }: { sources: ChatSource[] }) {
  if (sources.length === 0) return null
  const cited = sources.filter((s) => s.cited).length
  return (
    <details className="group mt-3 rounded-xl border border-slate-700 bg-slate-900/70 text-xs">
      <summary className="flex cursor-pointer select-none list-none items-center justify-between gap-2 rounded-xl px-3 py-2 text-slate-300 hover:text-slate-100 [&::-webkit-details-marker]:hidden">
        <span>Sources ({sources.length}{cited > 0 ? `, ${cited} cited` : ''})</span>
        <span aria-hidden="true" className="flex items-center gap-1">
          {sources.slice(0, 4).map((s) => (
            <span key={s.ref} className={`rounded-md px-1.5 py-0.5 font-mono text-[10px] ${s.cited ? 'bg-indigo-400/20 text-indigo-200' : 'bg-slate-700 text-slate-400'}`}>{s.ref}</span>
          ))}
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" className="ml-1 transition-transform duration-200 group-open:rotate-180"><path d="m6 9 6 6 6-6" /></svg>
        </span>
      </summary>
      <ul className="space-y-2 px-2 pb-2">
        {sources.map((s) => (
          <li key={s.ref} className={`rounded-lg border p-2.5 ${s.cited ? 'border-indigo-400/40 bg-indigo-400/5' : 'border-slate-700'}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-indigo-300">[{s.ref}]</span>
              <span className="rounded bg-slate-700 px-1.5 py-0.5">{TYPE_LABEL[s.type] ?? s.type}</span>
              <span className="font-medium text-slate-200">{s.title}</span>
              {s.cited && <span className="text-indigo-300">cited</span>}
            </div>
            <p className="mt-1 whitespace-pre-wrap text-slate-300">{s.snippet}</p>
            <p className="mt-1 text-slate-500">
              data as of {new Date(s.asOf).toLocaleString()} · match {Math.round(s.score * 100)}%
            </p>
          </li>
        ))}
      </ul>
    </details>
  )
}

function SuggestionCard({ s, onChange }: { s: ChatSuggestion; onChange: (s: ChatSuggestion) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const approve = async () => {
    setBusy(true)
    setError('')
    try {
      const rule = await api.approveSuggestion(s.id)
      onChange({ ...s, status: 'APPROVED', createdRuleId: rule.id })
      notify('Rule created from the suggestion')
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fieldErrors)[0] ?? e.message) : 'Could not create the rule')
    } finally {
      setBusy(false)
    }
  }
  const reject = async () => {
    setBusy(true)
    setError('')
    try {
      onChange(await api.rejectSuggestion(s.id))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not reject the suggestion')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-3 rounded-lg border border-indigo-500/40 bg-indigo-500/10 p-3 text-sm">
      <p className="font-medium text-indigo-200">Suggested automation rule</p>
      <p className="mt-1 text-slate-100">{s.summary}</p>
      <p className="mt-1 text-slate-300">{s.description}</p>
      {s.rationale && <p className="mt-1 text-xs text-slate-400">Why: {s.rationale}</p>}
      {s.status === 'PENDING' ? (
        <>
          <p className="mt-2 text-xs text-slate-400">
            Nothing has been created. It becomes a rule only if you approve it, and it expires {new Date(s.expiresAt).toLocaleTimeString()}.
          </p>
          <div className="mt-2 flex gap-2">
            <button type="button" disabled={busy} onClick={approve} className="rounded-md bg-green-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-green-600 disabled:opacity-60">
              Approve and create rule
            </button>
            <button type="button" disabled={busy} onClick={reject} className="rounded-md bg-slate-700 px-3 py-1.5 text-xs hover:bg-slate-600 disabled:opacity-60">
              Reject
            </button>
          </div>
        </>
      ) : (
        <p className="mt-2 text-xs text-slate-300">
          {s.status === 'APPROVED' && (
            <>Approved: the rule was created. <Link to="/automation" className="text-indigo-300 underline">See it under Automation</Link>.</>
          )}
          {s.status === 'REJECTED' && 'Rejected: nothing was created.'}
          {s.status === 'EXPIRED' && 'Expired: nothing was created. Ask again if you still want it.'}
        </p>
      )}
      {error && <p className="mt-2 text-xs text-red-400" role="alert">{error}</p>}
    </div>
  )
}

const TOOL_LABEL: Record<string, string> = {
  get_current_readings: 'latest readings',
  get_sensor_statistics: 'statistics',
  get_sensor_trend: 'trend',
  compare_periods: 'comparison',
  get_motion_activity: 'motion activity',
  get_device_status: 'device status',
  get_anomalies: 'anomalies',
  get_automation_history: 'automation history',
  get_security_events: 'security events',
  get_gas_incidents: 'gas incidents',
  get_command_history: 'LED command history',
  get_system_health: 'system health',
  search_knowledge: 'help and notes',
  create_report: 'report',
  propose_device_command: 'command proposal',
}

function argsText(json: string): string {
  try {
    const o = JSON.parse(json) as Record<string, unknown>
    return Object.entries(o).map(([k, v]) => `${k}: ${String(v)}`).join(', ')
  } catch {
    return ''
  }
}

interface Props {
  message: ChatMessage
  onSuggestionChange: (messageId: string, s: ChatSuggestion) => void
  /** A follow-up question was picked. */
  onFollowUp?: (q: string) => void
  /** The user wants to review a proposed device command (it is sent only after they confirm). */
  onAction?: (a: PendingDeviceAction) => void
  /** Hide follow-ups (they are only useful on the latest answer). */
  latest?: boolean
  /** Text-to-speech playback of this answer (omitted when the browser cannot speak). */
  onReadAloud?: (text: string) => void
}

export default function ChatMessageView({ message, onSuggestionChange, onFollowUp, onAction, latest = false, onReadAloud }: Props) {
  if (message.role === 'user') {
    return (
      <div className="flex justify-end">
        <p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-linear-to-br from-indigo-400 to-indigo-500 px-4 py-2.5 text-[15px] font-medium text-white shadow-[0_8px_24px_-14px_rgba(113,59,80,0.6)]">{message.content}</p>
      </div>
    )
  }
  const extras = message.extras
  const fromData = message.sources.some((s) => s.type === 'LIVE_DATA')
  const note = message.status
    ? (message.status === 'NOT_CONFIGURED' || message.status === 'LLM_UNAVAILABLE') && fromData
      ? { label: message.status === 'NOT_CONFIGURED' ? 'No AI model: answer built directly from your data' : 'AI model unavailable: answer built directly from your data', tone: 'bg-amber-500/20 text-amber-300' }
      : STATUS_NOTE[message.status]
    : null
  const tools = extras?.tools ?? []
  return (
    <div className="flex items-start justify-start gap-2.5">
      <LogoMark size={30} className="mt-0.5 shrink-0 drop-shadow" />
      <div className="min-w-0 max-w-[92%] flex-1 rounded-2xl rounded-tl-md border border-slate-700 bg-slate-700/60 px-4 py-3 text-[15px] leading-relaxed sm:flex-none">
        {extras?.safety && (
          <p role="alert" className="mb-3 flex gap-2 rounded-lg border border-red-500/50 bg-red-500/15 p-2.5 text-sm text-red-200">
            <AlertIcon aria-hidden="true" width={18} height={18} className="mt-0.5 shrink-0" />
            <span><strong className="font-semibold">Safety: </strong>{extras.safety}</span>
          </p>
        )}
        {note && <p className={`mb-2 inline-block rounded px-2 py-0.5 text-xs font-medium ${note.tone}`}>{note.label}</p>}
        <Markdown text={message.content} />
        {extras && extras.visuals.length > 0 && (
          <div className="mt-3 grid gap-3">
            {extras.visuals.map((v, i) => <VisualView key={i} visual={v} />)}
          </div>
        )}
        {extras?.actions.map((a, i) => (
          <div key={i} className="mt-3 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
            <p className="font-medium text-amber-200">Device command waiting for your confirmation</p>
            <p className="mt-1 text-slate-100">{a.description}</p>
            <p className="mt-1 text-xs text-slate-400">Nothing has been sent. Review it and confirm to send it to the device.</p>
            {onAction && (
              <button type="button" onClick={() => onAction(a)} className="mt-2 rounded-md bg-amber-500/80 px-3 py-1.5 text-xs font-medium text-slate-100 hover:bg-amber-400">
                Review and confirm
              </button>
            )}
          </div>
        ))}
        {extras && extras.reports.length > 0 && (
          <p className="mt-3 text-sm">
            <Link to={`/reports?open=${extras.reports[0]}`} className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-500/20 px-3 py-1.5 text-indigo-200 hover:bg-indigo-500/30">
              <ReportIcon width={16} height={16} aria-hidden="true" />Open the report (preview and PDF)
            </Link>
          </p>
        )}
        {message.warnings.length > 0 && (
          <ul className="mt-2 space-y-1 rounded bg-amber-500/10 p-2 text-xs text-amber-300" role="note">
            {message.warnings.map((w) => <li key={w}>Check this: {w}</li>)}
          </ul>
        )}
        {message.suggestions.map((s) => (
          <SuggestionCard key={s.id} s={s} onChange={(next) => onSuggestionChange(message.id, next)} />
        ))}
        <SourceList sources={message.sources} />
        {onReadAloud && message.content.trim() !== '' && (
          <button type="button" onClick={() => onReadAloud(message.content)} aria-label="Read this answer aloud"
            className="mt-2 text-xs text-indigo-300 hover:underline">
            Read aloud
          </button>
        )}
        {tools.length > 0 && (
          <p className="mt-2 text-[11px] text-slate-400">
            Data used:{' '}
            {tools.map((t, i) => (
              <span key={i} title={t.error ?? argsText(t.arguments)} className={t.ok ? '' : 'text-amber-300 line-through'}>
                {i > 0 ? ' · ' : ''}{TOOL_LABEL[t.tool] ?? t.tool}{t.ok && argsText(t.arguments) ? ` (${argsText(t.arguments)})` : ''}
              </span>
            ))}
          </p>
        )}
        {latest && onFollowUp && extras && extras.followUps.length > 0 && (
          <div className="mt-3 flex flex-wrap gap-2" aria-label="Suggested follow-up questions">
            {extras.followUps.map((q) => (
              <button key={q} type="button" onClick={() => onFollowUp(q)}
                className="rounded-full border border-slate-600 bg-slate-800/70 px-3 py-1 text-xs text-slate-200 transition hover:border-indigo-400 hover:text-indigo-200">
                {q}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
