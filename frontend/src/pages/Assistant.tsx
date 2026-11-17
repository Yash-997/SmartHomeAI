import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import {
  api,
  ApiError,
  type AssistantStatus,
  type ChatMessage,
  type ChatSuggestion,
  type Command,
  type Conversation,
  type PendingDeviceAction,
} from '../api'
import ChatMessageView from '../components/ChatMessageView'
import CommandCard from '../components/CommandCard'
import { ChatIcon, ChevronDownIcon, PlusIcon } from '../ui/icons'
import { LogoMark } from '../ui/Logo'
import Shell from '../components/Shell'
import KnowledgePanel from '../components/KnowledgePanel'
import { FINAL, outcomeText, type CommandItem } from '../voice/commands'
import { confirmationReply, deviceNamedIn, devicesFor, parseDeviceCommand } from '../voice/intent'
import { useSpeechSynthesis } from '../voice/useSpeechSynthesis'

const EXAMPLES = [
  'What is the current temperature and humidity of my devices?',
  'Was it warmer today than yesterday?',
  'Show the humidity trend for the last 7 days',
  'When was motion detected today?',
  'Were there any anomalies or security events this week?',
  'Generate a weekly report',
]

const POLL_MS = 1000
let nextItemId = 0

/** Polls the command until the device has answered (or it can no longer answer). */
async function waitForAck(deviceId: string, first: Command, alive: () => boolean): Promise<Command> {
  let c = first
  const deadline = Math.min(Date.parse(c.expiresAt) + 15000, Date.now() + 60000)
  while (!FINAL.includes(c.status) && Date.now() < deadline && alive()) {
    await new Promise((r) => setTimeout(r, POLL_MS))
    try {
      c = await api.getCommand(deviceId, c.id)
    } catch {
      // a missed poll is retried; the deadline still applies
    }
  }
  return c
}

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
  const [progress, setProgress] = useState('')
  const [error, setError] = useState('')
  const [items, setItems] = useState<CommandItem[]>([])
  const fetched = useRef<Set<string>>(new Set())
  const endRef = useRef<HTMLDivElement | null>(null)
  // Synchronous guards: React state updates are not visible to a second call in the same tick.
  const inFlight = useRef(false)
  const itemsRef = useRef<CommandItem[]>([])
  const mounted = useRef(true)

  const tts = useSpeechSynthesis()

  useEffect(() => {
    itemsRef.current = items
  }, [items])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

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
  const convKey = active ?? 'draft'
  const shownItems = items.filter((i) => i.convKey === convKey)

  useEffect(() => {
    const calm = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    endRef.current?.scrollIntoView?.({ behavior: calm ? 'auto' : 'smooth', block: 'end' })
  }, [messages, pending, items])

  const select = (id: string | null) => {
    setActive(id)
    setThreadError('')
    setError('')
  }

  // ---- questions ------------------------------------------------------------------------------

  const ask = async (q: string) => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(q)
    setProgress('')
    setError('')
    try {
      const reply = typeof api.chatStream === 'function'
        ? await api.chatStream(q, active ?? undefined, (p) => setProgress(p.detail))
        : await api.chat(q, active ?? undefined)
      const id = reply.conversationId
      fetched.current.add(id)
      setThreads((t) => ({ ...t, [id]: [...(active ? (t[active] ?? []) : draft), reply.userMessage, reply.assistantMessage] }))
      if (!active) {
        setDraft([])
        setItems((list) => list.map((i) => (i.convKey === 'draft' ? { ...i, convKey: id } : i)))
        setActive(id)
      }
      setInput('')
      loadConversations()
      loadStatus()
    } catch (e) {
      const msg = e instanceof ApiError ? (e.fieldErrors.message ?? e.message) : 'Something went wrong. Please try again.'
      setError(msg)
    } finally {
      inFlight.current = false
      setPending(null)
    }
  }

  // ---- device commands: proposed here, sent only after confirmation, through the normal command API ----

  const patchItem = (id: string, fn: (i: CommandItem) => CommandItem) => {
    itemsRef.current = itemsRef.current.map((i) => (i.id === id ? fn(i) : i))
    setItems(itemsRef.current)
  }

  const propose = async (said: string) => {
    const intent = parseDeviceCommand(said)
    if (!intent) return false
    const item: CommandItem = {
      id: `cmd-${++nextItemId}`,
      convKey,
      afterIndex: messages?.length ?? 0,
      said,
      action: intent.action,
      value: intent.kind === 'command' ? intent.value : 'off',
      phase: intent.kind === 'refused' ? 'refused' : 'loading',
      devices: [],
      deviceId: null,
      command: null,
      message: intent.kind === 'refused' ? intent.reason : '',
    }
    itemsRef.current = [...itemsRef.current, item]
    setItems(itemsRef.current)
    setInput('')
    if (intent.kind === 'refused') return true
    try {
      const eligible = devicesFor(await api.listDevices(), intent.action, intent.value)
      if (eligible.length === 0) {
        const msg = 'None of your devices has this output. Pair a device on the Devices page first.'
        patchItem(item.id, (i) => ({ ...i, phase: 'error', message: msg }))
        return true
      }
      const chosen = deviceNamedIn(eligible, said) ?? (eligible.length === 1 ? eligible[0] : null)
      patchItem(item.id, (i) => ({ ...i, phase: 'confirm', devices: eligible, deviceId: chosen?.id ?? null }))
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'Could not load your devices.'
      patchItem(item.id, (i) => ({ ...i, phase: 'error', message: msg }))
    }
    return true
  }

  const confirm = async (id: string) => {
    const item = itemsRef.current.find((i) => i.id === id)
    if (!item || item.phase !== 'confirm' || !item.deviceId) return
    const deviceId = item.deviceId
    patchItem(id, (i) => ({ ...i, phase: 'sending', message: '' }))
    try {
      const sent = await api.sendCommand(deviceId, item.action, item.value)
      patchItem(id, (i) => ({ ...i, phase: 'waiting', command: sent }))
      const final = await waitForAck(deviceId, sent, () => mounted.current)
      if (!mounted.current) return
      const text = outcomeText({ ...item, deviceId }, final)
      patchItem(id, (i) => ({ ...i, phase: 'done', command: final, message: text }))
    } catch (e) {
      const msg = e instanceof ApiError ? (e.fieldErrors.value ?? e.fieldErrors.action ?? e.message) : 'Could not send the command.'
      patchItem(id, (i) => ({ ...i, phase: 'error', message: msg }))
    }
  }

  /** A command the assistant prepared goes through the same confirm-then-send card as a typed command. */
  const reviewAction = async (a: PendingDeviceAction) => {
    const item: CommandItem = {
      id: `cmd-${++nextItemId}`,
      convKey,
      afterIndex: messages?.length ?? 0,
      said: a.description,
      action: a.action,
      value: a.value,
      phase: 'loading',
      devices: [],
      deviceId: null,
      command: null,
      message: '',
    }
    itemsRef.current = [...itemsRef.current, item]
    setItems(itemsRef.current)
    try {
      const eligible = devicesFor(await api.listDevices(), a.action, a.value)
      const target = eligible.find((d) => d.id === a.deviceId)
      if (!target) {
        patchItem(item.id, (i) => ({ ...i, phase: 'error', message: 'That device can no longer take this command.' }))
        return
      }
      patchItem(item.id, (i) => ({ ...i, phase: 'confirm', devices: eligible, deviceId: target.id }))
    } catch (e) {
      patchItem(item.id, (i) => ({ ...i, phase: 'error', message: e instanceof ApiError ? e.message : 'Could not load your devices.' }))
    }
  }

  const cancelItem = (id: string) => {
    patchItem(id, (i) => (i.phase === 'confirm' || i.phase === 'loading' ? { ...i, phase: 'cancelled' } : i))
  }

  // ---- one entry point for everything the user types ------------------------------------------

  const submit = async (text: string) => {
    const q = text.trim()
    if (!q || inFlight.current) return
    const waiting = [...itemsRef.current].reverse().find((i) => i.convKey === convKey && i.phase === 'confirm')
    if (waiting) {
      const reply = confirmationReply(q)
      if (reply) {
        setInput('')
        if (reply === 'yes') {
          if (waiting.deviceId) void confirm(waiting.id)
          else setError('Choose the device on screen first, then confirm.')
        } else cancelItem(waiting.id)
        return
      }
    }
    if (await propose(q)) return
    await ask(q)
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
      setItems((list) => list.filter((i) => i.convKey !== id))
      if (active === id) select(null)
      loadConversations()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete the conversation')
    }
  }

  const status = statusLoad?.status
  const max = status?.limits.maxMessageChars ?? 1000
  const busy = pending !== null
  const count = messages?.length ?? 0

  const card = (i: CommandItem) => (
    <CommandCard key={i.id} item={i}
      onDevice={(d) => patchItem(i.id, (x) => ({ ...x, deviceId: d }))}
      onConfirm={() => void confirm(i.id)} onCancel={() => cancelItem(i.id)} />
  )

  return (
    <Shell wide>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Assistant</h1>
            <p className="mt-1.5 text-sm text-slate-400">Ask about your home.</p>
          </div>
        </div>

        {!statusLoad && <p className="text-sm text-slate-400">Loading...</p>}
        {statusLoad?.error && (
          <p className="rounded bg-red-500/10 p-3 text-sm text-red-400">{statusLoad.error} <button onClick={loadStatus} className="underline">Try again</button></p>
        )}
        {status && (
          <div className="space-y-2">
            {!status.llm.configured && (
              <p className="rounded-xl bg-amber-500/10 p-3 text-sm text-amber-300" role="status">
                No AI language model is set up ({status.llm.note}). You will still get the matching stored records as references, but no written answers.
                See the README section on environment variables.
              </p>
            )}
            <details className="text-xs text-slate-500">
              <summary className="cursor-pointer hover:text-slate-300">About this assistant</summary>
              <p className="mt-2 max-w-3xl rounded-xl bg-slate-800 p-3 text-slate-400" role="note">
                {status.notice} Model: {status.llm.configured ? `${status.llm.provider} / ${status.llm.model}` : 'none'} · search: {status.embedding.provider}
                {status.embedding.note ? ` (${status.embedding.note})` : ''} · indexed: {status.index.yourChunks} of your records, {status.index.sharedChunks} help passages.
              </p>
            </details>
          </div>
        )}

        <div className="grid gap-6 lg:grid-cols-[15rem_1fr] xl:grid-cols-[16rem_1fr]">
          <aside className="order-2 space-y-6 lg:order-1">
            <section className="card p-4" aria-labelledby="conv-heading">
              <div className="flex items-center justify-between">
                <h3 id="conv-heading" className="font-semibold">Conversations</h3>
              </div>
              {conversations === null && <p className="mt-2 text-sm text-slate-400">Loading...</p>}
              {conversations?.length === 0 && <p className="mt-2 text-sm text-slate-500">No conversations yet.</p>}
              <ul className="mt-2 space-y-1">
                {conversations?.map((c) => (
                  <li key={c.id} className={`flex items-center justify-between gap-1 rounded-lg px-2.5 py-2 text-sm transition-colors ${c.id === active ? 'bg-slate-700' : 'hover:bg-slate-700/50'}`}>
                    <button type="button" onClick={() => select(c.id)} className="min-w-0 flex-1 truncate text-left" title={c.title} aria-current={c.id === active}>{c.title}</button>
                    <button type="button" onClick={() => remove(c.id)} className="text-xs text-slate-400 hover:text-red-300" aria-label={`Delete conversation ${c.title}`}>x</button>
                  </li>
                ))}
              </ul>
            </section>
            {status && (
              <details className="card group">
                <summary className="flex cursor-pointer list-none items-center justify-between gap-2 rounded-2xl p-4 text-sm font-semibold marker:hidden hover:bg-slate-700/30 [&::-webkit-details-marker]:hidden">
                  Notes
                  <ChevronDownIcon width={16} height={16} aria-hidden="true" className="text-slate-400 transition-transform group-open:rotate-180" />
                </summary>
                <div className="flat-panel px-4 pb-4">
                  <KnowledgePanel maxChars={status.limits.maxDocumentChars} maxDocs={status.limits.maxDocuments} onChanged={loadStatus} />
                </div>
              </details>
            )}
          </aside>

          <section className="card order-1 flex min-h-[26rem] flex-col gap-4 p-4 sm:p-5 lg:order-2" aria-label="Chat">
            <div className="flex items-center justify-between gap-2 border-b border-slate-700/60 pb-3">
              <p className="flex min-w-0 items-start gap-2 text-sm font-medium text-slate-300"
                title={active ? (conversations?.find((c) => c.id === active)?.title ?? 'Conversation') : 'New conversation'}>
                <ChatIcon aria-hidden="true" width={16} height={16} className="mt-0.5 shrink-0 text-indigo-400" />
                <span className="line-clamp-2 break-words">{active ? (conversations?.find((c) => c.id === active)?.title ?? 'Conversation') : 'New conversation'}</span>
              </p>
              <button type="button" onClick={() => select(null)} className="btn-secondary inline-flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs">
                <PlusIcon width={14} height={14} />New conversation
              </button>
            </div>
            <div className="max-h-[62vh] min-h-[9rem] flex-1 space-y-4 overflow-y-auto pb-2 pr-1" aria-live="polite">
              {loadingThread && !threadError && <p className="text-sm text-slate-400">Loading conversation...</p>}
              {threadError && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400">{threadError}</p>}
              {!loadingThread && count === 0 && shownItems.length === 0 && pending === null && (
                <div className="py-4">
                  <p className="text-lg font-semibold">What would you like to know?</p>
                  <p className="mt-1 text-sm text-slate-400">Try one of these:</p>
                  <ul className="mt-4 grid gap-3 sm:grid-cols-2">
                    {EXAMPLES.map((ex) => (
                      <li key={ex}>
                        <button type="button" onClick={() => void submit(ex)}
                          className="flex h-full w-full items-start gap-3 rounded-2xl bg-slate-900/60 p-4 text-left text-sm text-slate-200 transition hover:-translate-y-0.5 hover:bg-slate-700">
                          <ChatIcon aria-hidden="true" width={18} height={18} className="mt-0.5 shrink-0 text-indigo-400" />
                          <span>{ex}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {messages?.map((m, idx) => (
                <Fragment key={m.id}>
                  {shownItems.filter((i) => i.afterIndex === idx).map(card)}
                  <ChatMessageView message={m} onSuggestionChange={changeSuggestion} latest={idx === count - 1 && pending === null}
                  onFollowUp={(q) => void submit(q)} onAction={(a) => void reviewAction(a)}
                  onReadAloud={tts.supported ? (text) => tts.speak(text) : undefined} />
                </Fragment>
              ))}
              {shownItems.filter((i) => i.afterIndex >= count).map(card)}
              {pending !== null && (
                <>
                  <div className="flex justify-end"><p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-linear-to-br from-indigo-400 to-indigo-500 px-4 py-2.5 text-[15px] font-medium text-white">{pending}</p></div>
                  <div className="flex items-start gap-2.5">
                    <LogoMark size={30} className="mt-0.5 shrink-0" />
                    <p className="flex items-center gap-3 rounded-2xl rounded-tl-md border border-slate-700 bg-slate-700/60 px-4 py-3 text-sm text-slate-300" role="status">
                      <span aria-hidden="true" className="flex gap-1"><span className="typing-dot" /><span className="typing-dot" /><span className="typing-dot" /></span>
                      {progress ? `${progress}...` : 'Searching your records and thinking...'}
                    </p>
                  </div>
                </>
              )}
              <div ref={endRef} />
            </div>


            {tts.speaking && (
              <p className="flex items-center justify-between rounded bg-slate-900 px-3 py-2 text-sm text-slate-300" role="status">
                Speaking... <button type="button" onClick={tts.stop} className="rounded-md bg-slate-700 px-2 py-1 text-xs hover:bg-slate-600">Stop speaking</button>
              </p>
            )}

            {error && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}

            <form onSubmit={(e) => { e.preventDefault(); void submit(input) }} className="flex items-end gap-2 border-t border-slate-700/60 pt-4">
              <label className="flex-1 text-xs text-slate-400">
                <span className="sr-only">Your question</span>
                <textarea id="assistant-input" aria-label="Your question" value={input} onChange={(e) => setInput(e.target.value)} maxLength={max} rows={2}
                  placeholder="Ask about your devices..."
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void submit(input) } }}
                  className="field-input w-full resize-none rounded-2xl px-4 py-3 text-[15px] text-slate-100 placeholder:text-slate-500" />
                <span className="mt-0.5 block text-right">{input.length}/{max}</span>
              </label>
              <button type="submit" disabled={busy || input.trim() === ''}
                className="btn-primary mb-5 flex items-center gap-2 rounded-xl px-6 py-3 text-sm disabled:opacity-60">
                {busy && <span className="spinner" aria-hidden="true" />}
                {busy ? 'Asking...' : 'Ask'}
              </button>
            </form>
          </section>
        </div>
    </Shell>
  )
}
