import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api,
  ApiError,
  type AssistantStatus,
  type ChatMessage,
  type ChatSuggestion,
  type Conversation,
} from '../api'
import ChatMessageView from '../components/ChatMessageView'
import Header from '../components/Header'
import KnowledgePanel from '../components/KnowledgePanel'

const EXAMPLES = [
  'What is the current temperature and humidity of my devices?',
  'Were there any anomalies recently, and what do they mean?',
  'Which automation rules do I have and when did they last run?',
  'Suggest a rule that turns the PIR LED on when motion is detected after 22:00.',
]

type StatusLoad = { status?: AssistantStatus; error?: string }

export default function Assistant() {
  const [statusLoad, setStatusLoad] = useState<StatusLoad | null>(null)
  const [conversations, setConversations] = useState<Conversation[] | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const [threads, setThreads] = useState<Record<string, ChatMessage[]>>({})
  const [draft, setDraft] = useState<ChatMessage[]>([])
  const [threadError, setThreadError] = useState('')
  const [input, setInput] = useState('')
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState('')
  const fetched = useRef<Set<string>>(new Set())
  const endRef = useRef<HTMLDivElement | null>(null)

  const loadStatus = useCallback(() => {
    api
      .assistantStatus()
      .then((status) => setStatusLoad({ status }))
      .catch((e) => setStatusLoad({ error: e instanceof ApiError ? e.message : 'Could not load the assistant status' }))
  }, [])

  const loadConversations = useCallback(() => {
    api
      .conversations()
      .then(setConversations)
      .catch(() => setConversations((c) => c ?? []))
  }, [])

  useEffect(() => {
    loadStatus()
    loadConversations()
  }, [loadStatus, loadConversations])

  useEffect(() => {
    if (!active || fetched.current.has(active)) return
    fetched.current.add(active)
    api
      .conversationMessages(active)
      .then((m) => setThreads((t) => ({ ...t, [active]: m })))
      .catch((e) => {
        fetched.current.delete(active)
        setThreadError(e instanceof ApiError ? e.message : 'Could not load this conversation')
      })
  }, [active])

  const messages = active ? threads[active] : draft
  const loadingThread = active !== null && !threads[active]

  useEffect(() => {
    const calm = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    endRef.current?.scrollIntoView?.({ behavior: calm ? 'auto' : 'smooth', block: 'end' })
  }, [messages, pending])

  const select = (id: string | null) => {
    setActive(id)
    setThreadError('')
    setError('')
  }

  const send = async (text: string) => {
    const q = text.trim()
    if (!q || pending !== null) return
    setPending(q)
    setError('')
    try {
      const reply = await api.chat(q, active ?? undefined)
      const id = reply.conversationId
      fetched.current.add(id)
      setThreads((t) => ({ ...t, [id]: [...(active ? (t[active] ?? []) : draft), reply.userMessage, reply.assistantMessage] }))
      if (!active) {
        setDraft([])
        setActive(id)
      }
      setInput('')
      loadConversations()
      loadStatus()
    } catch (e) {
      setError(e instanceof ApiError ? (e.fieldErrors.message ?? e.message) : 'Something went wrong. Please try again.')
    } finally {
      setPending(null)
    }
  }

  const changeSuggestion = (messageId: string, next: ChatSuggestion) => {
    const patch = (list: ChatMessage[]) =>
      list.map((m) => (m.id === messageId ? { ...m, suggestions: m.suggestions.map((s) => (s.id === next.id ? next : s)) } : m))
    if (active) setThreads((t) => ({ ...t, [active]: patch(t[active] ?? []) }))
  }

  const remove = async (id: string) => {
    if (!window.confirm('Delete this conversation?')) return
    try {
      await api.deleteConversation(id)
      fetched.current.delete(id)
      setThreads((t) => {
        const { [id]: _removed, ...rest } = t
        return rest
      })
      if (active === id) select(null)
      loadConversations()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete the conversation')
    }
  }

  const status = statusLoad?.status
  const max = status?.limits.maxMessageChars ?? 1000

  return (
    <main className="min-h-screen bg-slate-900 p-6 text-slate-100">
      <div className="mx-auto max-w-6xl space-y-6">
        <Header />

        <div>
          <h2 className="text-lg font-semibold">Assistant</h2>
          <p className="text-sm text-slate-400">Ask about your own devices, readings, anomalies and automation rules. Answers cite the stored records they came from.</p>
        </div>

        {!statusLoad && <p className="text-sm text-slate-400">Loading...</p>}
        {statusLoad?.error && (
          <p className="rounded bg-red-500/10 p-3 text-sm text-red-400">{statusLoad.error} <button onClick={loadStatus} className="underline">Try again</button></p>
        )}
        {status && (
          <div className="space-y-2">
            {!status.llm.configured && (
              <p className="rounded-lg bg-amber-500/10 p-3 text-sm text-amber-300" role="status">
                No AI language model is set up ({status.llm.note}). You will still get the matching stored records as references, but no written answers.
                See the README section on environment variables.
              </p>
            )}
            <p className="rounded-lg bg-slate-800 p-3 text-xs text-slate-400" role="note">
              {status.notice} Model: {status.llm.configured ? `${status.llm.provider} / ${status.llm.model}` : 'none'} · search: {status.embedding.provider}
              {status.embedding.note ? ` (${status.embedding.note})` : ''} · indexed: {status.index.yourChunks} of your records, {status.index.sharedChunks} help passages.
            </p>
          </div>
        )}

        <div className="grid gap-6 lg:grid-cols-[16rem_1fr]">
          <aside className="space-y-6">
            <section className="rounded-xl bg-slate-800 p-4" aria-labelledby="conv-heading">
              <div className="flex items-center justify-between">
                <h3 id="conv-heading" className="font-semibold">Conversations</h3>
                <button type="button" onClick={() => select(null)} className="rounded-md bg-slate-700 px-2 py-1 text-xs hover:bg-slate-600">New</button>
              </div>
              {conversations === null && <p className="mt-2 text-sm text-slate-400">Loading...</p>}
              {conversations?.length === 0 && <p className="mt-2 text-sm text-slate-500">No conversations yet.</p>}
              <ul className="mt-2 space-y-1">
                {conversations?.map((c) => (
                  <li key={c.id} className={`flex items-center justify-between gap-1 rounded px-2 py-1.5 text-sm ${c.id === active ? 'bg-slate-700' : 'hover:bg-slate-700/50'}`}>
                    <button type="button" onClick={() => select(c.id)} className="truncate text-left" title={c.title} aria-current={c.id === active}>{c.title}</button>
                    <button type="button" onClick={() => remove(c.id)} className="text-xs text-slate-400 hover:text-red-300" aria-label={`Delete conversation ${c.title}`}>x</button>
                  </li>
                ))}
              </ul>
            </section>
            {status && <KnowledgePanel maxChars={status.limits.maxDocumentChars} maxDocs={status.limits.maxDocuments} onChanged={loadStatus} />}
          </aside>

          <section className="flex min-h-[28rem] flex-col rounded-xl bg-slate-800/60 p-4" aria-label="Chat">
            <div className="flex-1 space-y-4 overflow-y-auto pb-2" aria-live="polite">
              {loadingThread && !threadError && <p className="text-sm text-slate-400">Loading conversation...</p>}
              {threadError && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400">{threadError}</p>}
              {!loadingThread && (messages?.length ?? 0) === 0 && pending === null && (
                <div className="rounded-lg bg-slate-900 p-4 text-sm text-slate-300">
                  <p>Try one of these:</p>
                  <ul className="mt-2 space-y-1">
                    {EXAMPLES.map((ex) => (
                      <li key={ex}><button type="button" onClick={() => send(ex)} className="text-left text-indigo-300 hover:underline">{ex}</button></li>
                    ))}
                  </ul>
                </div>
              )}
              {messages?.map((m) => <ChatMessageView key={m.id} message={m} onSuggestionChange={changeSuggestion} />)}
              {pending !== null && (
                <>
                  <div className="flex justify-end"><p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-indigo-600/70 px-4 py-2 text-sm">{pending}</p></div>
                  <p className="text-sm text-slate-400" role="status">Searching your records and thinking...</p>
                </>
              )}
              <div ref={endRef} />
            </div>

            {error && <p className="mb-2 rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}
            <form onSubmit={(e) => { e.preventDefault(); void send(input) }} className="flex items-end gap-2">
              <label className="flex-1 text-xs text-slate-400">
                <span className="sr-only">Your question</span>
                <textarea value={input} onChange={(e) => setInput(e.target.value)} maxLength={max} rows={2} placeholder="Ask about your devices..."
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send(input) } }}
                  className="w-full rounded-md border border-slate-600 bg-slate-900 px-3 py-2 text-sm text-slate-100" />
                <span className="mt-0.5 block text-right">{input.length}/{max}</span>
              </label>
              <button type="submit" disabled={pending !== null || input.trim() === ''}
                className="mb-5 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
                {pending !== null ? 'Asking...' : 'Ask'}
              </button>
            </form>
          </section>
        </div>
      </div>
    </main>
  )
}
