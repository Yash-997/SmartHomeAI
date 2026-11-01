import { useState } from 'react'
import { Link } from 'react-router-dom'
import { notify } from '../ui/toastStore'
import { api, ApiError, type AnswerStatus, type ChatMessage, type ChatSource, type ChatSuggestion } from '../api'

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
  DOCUMENT: 'Your document',
  KNOWLEDGE: 'Help text',
}

function SourceList({ sources }: { sources: ChatSource[] }) {
  if (sources.length === 0) return null
  const cited = sources.filter((s) => s.cited).length
  return (
    <details className="mt-3 rounded-lg bg-slate-900/70 p-2 text-xs">
      <summary className="cursor-pointer select-none text-slate-300">
        Sources ({sources.length}{cited > 0 ? `, ${cited} cited` : ''})
      </summary>
      <ul className="mt-2 space-y-2">
        {sources.map((s) => (
          <li key={s.ref} className={`rounded border p-2 ${s.cited ? 'border-indigo-500/60' : 'border-slate-700'}`}>
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
            <button type="button" disabled={busy} onClick={approve} className="rounded-md bg-green-700 px-3 py-1.5 text-xs font-medium hover:bg-green-600 disabled:opacity-60">
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

interface Props {
  message: ChatMessage
  onSuggestionChange: (messageId: string, s: ChatSuggestion) => void
}

export default function ChatMessageView({ message, onSuggestionChange }: Props) {
  if (message.role === 'user') {
    return (
      <div className="flex justify-end">
        <p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2 text-sm">{message.content}</p>
      </div>
    )
  }
  const note = message.status ? STATUS_NOTE[message.status] : null
  return (
    <div className="flex justify-start">
      <div className="max-w-[92%] rounded-2xl rounded-bl-sm bg-slate-800 px-4 py-3 text-sm">
        {note && <p className={`mb-2 inline-block rounded px-2 py-0.5 text-xs font-medium ${note.tone}`}>{note.label}</p>}
        <p className="whitespace-pre-wrap text-slate-100">{message.content}</p>
        {message.warnings.length > 0 && (
          <ul className="mt-2 space-y-1 rounded bg-amber-500/10 p-2 text-xs text-amber-300" role="note">
            {message.warnings.map((w) => <li key={w}>Check this: {w}</li>)}
          </ul>
        )}
        {message.suggestions.map((s) => (
          <SuggestionCard key={s.id} s={s} onChange={(next) => onSuggestionChange(message.id, next)} />
        ))}
        <SourceList sources={message.sources} />
      </div>
    </div>
  )
}
