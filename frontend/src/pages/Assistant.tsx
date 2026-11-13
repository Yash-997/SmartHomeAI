import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import {
  api,
  ApiError,
  type AssistantStatus,
  type ChatMessage,
  type ChatSuggestion,
  type Command,
  type Conversation,
} from '../api'
import ChatMessageView from '../components/ChatMessageView'
import CommandCard from '../components/CommandCard'
import { ChatIcon, ChevronDownIcon, PlusIcon } from '../ui/icons'
import Shell from '../components/Shell'
import KnowledgePanel from '../components/KnowledgePanel'
import VoicePanel from '../components/VoicePanel'
import { FINAL, outcomeText, type CommandItem } from '../voice/commands'
import { confirmationReply, describeCommand, deviceNamedIn, devicesFor, parseDeviceCommand } from '../voice/intent'
import { useSpeechRecognition } from '../voice/useSpeechRecognition'
import { useSpeechSynthesis } from '../voice/useSpeechSynthesis'

const EXAMPLES = [
  'What is the current temperature and humidity of my devices?',
  'Were there any anomalies recently, and what do they mean?',
  'Which automation rules do I have and when did they last run?',
  'Suggest a rule that turns the PIR LED on when motion is detected after 22:00.',
]

const VOICE_EXAMPLES = ['What is the current temperature?', 'Explain the latest gas sensor readings.', 'Turn on the PIR indicator LED.']

type Mode = 'text' | 'voice'
const MODE_KEY = 'smarthome.assistant.mode'
const POLL_MS = 1000
let nextItemId = 0

function readMode(): Mode {
  try {
    return localStorage.getItem(MODE_KEY) === 'voice' ? 'voice' : 'text'
  } catch {
    return 'text'
  }
}

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
  const [error, setError] = useState('')
  const [mode, setModeState] = useState<Mode>(readMode)
  const [items, setItems] = useState<CommandItem[]>([])
  const [lastAnswer, setLastAnswer] = useState<ChatMessage | null>(null)
  const fetched = useRef<Set<string>>(new Set())
  const endRef = useRef<HTMLDivElement | null>(null)
  // Synchronous guards: React state updates are not visible to a second call in the same tick.
  const inFlight = useRef(false)
  const itemsRef = useRef<CommandItem[]>([])
  const modeRef = useRef<Mode>(mode)
  const mounted = useRef(true)

  const tts = useSpeechSynthesis()

  useEffect(() => {
    itemsRef.current = items
  }, [items])
  useEffect(() => {
    modeRef.current = mode
  }, [mode])
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

  /** Answers are read aloud in voice mode, or when the question itself was spoken. */
  const sayIfVoice = (text: string, spoken: boolean) => {
    if (spoken || modeRef.current === 'voice') tts.speak(text)
  }

  // ---- questions: the same /api/assistant/chat call for typed and spoken input ----------------

  const ask = async (q: string, spoken: boolean) => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(q)
    setError('')
    try {
      const reply = await api.chat(q, active ?? undefined)
      const id = reply.conversationId
      fetched.current.add(id)
      setThreads((t) => ({ ...t, [id]: [...(active ? (t[active] ?? []) : draft), reply.userMessage, reply.assistantMessage] }))
      if (!active) {
        setDraft([])
        setItems((list) => list.map((i) => (i.convKey === 'draft' ? { ...i, convKey: id } : i)))
        setActive(id)
      }
      if (!spoken) setInput('')
      setLastAnswer(reply.assistantMessage)
      sayIfVoice(reply.assistantMessage.content, spoken)
      loadConversations()
      loadStatus()
    } catch (e) {
      const msg = e instanceof ApiError ? (e.fieldErrors.message ?? e.message) : 'Something went wrong. Please try again.'
      setError(msg)
      sayIfVoice(msg, spoken)
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

  const propose = async (said: string, spoken: boolean) => {
    const intent = parseDeviceCommand(said)
    if (!intent) return false
    const item: CommandItem = {
      id: `cmd-${++nextItemId}`,
      convKey,
      afterIndex: messages?.length ?? 0,
      said,
      spoken,
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
    if (!spoken) setInput('')
    if (intent.kind === 'refused') {
      sayIfVoice(intent.reason, spoken)
      return true
    }
    try {
      const eligible = devicesFor(await api.listDevices(), intent.action, intent.value)
      if (eligible.length === 0) {
        const msg = 'None of your devices has this output. Pair a device on the Devices page first.'
        patchItem(item.id, (i) => ({ ...i, phase: 'error', message: msg }))
        sayIfVoice(msg, spoken)
        return true
      }
      const chosen = deviceNamedIn(eligible, said) ?? (eligible.length === 1 ? eligible[0] : null)
      patchItem(item.id, (i) => ({ ...i, phase: 'confirm', devices: eligible, deviceId: chosen?.id ?? null }))
      const what = describeCommand(intent.action, intent.value)
      sayIfVoice(chosen ? `${what} on ${chosen.name}? Please confirm.` : `${what}. Which device? Choose it on screen, then confirm.`, spoken)
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'Could not load your devices.'
      patchItem(item.id, (i) => ({ ...i, phase: 'error', message: msg }))
      sayIfVoice(msg, spoken)
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
      sayIfVoice(text, item.spoken)
    } catch (e) {
      const msg = e instanceof ApiError ? (e.fieldErrors.value ?? e.fieldErrors.action ?? e.message) : 'Could not send the command.'
      patchItem(id, (i) => ({ ...i, phase: 'error', message: msg }))
      sayIfVoice(msg, item.spoken)
    }
  }

  const cancelItem = (id: string, spoken = false) => {
    patchItem(id, (i) => (i.phase === 'confirm' || i.phase === 'loading' ? { ...i, phase: 'cancelled' } : i))
    if (spoken) sayIfVoice('Cancelled. Nothing was sent.', true)
  }

  // ---- one entry point for everything the user types or says ----------------------------------

  const submit = async (text: string, spoken: boolean) => {
    const q = text.trim()
    if (!q || inFlight.current) return
    const waiting = [...itemsRef.current].reverse().find((i) => i.convKey === convKey && i.phase === 'confirm')
    if (waiting) {
      const reply = confirmationReply(q)
      if (reply) {
        if (!spoken) setInput('')
        if (reply === 'yes') {
          if (waiting.deviceId) void confirm(waiting.id)
          else sayIfVoice('Choose the device on screen first, then confirm.', spoken)
        } else cancelItem(waiting.id, spoken)
        return
      }
    }
    if (await propose(q, spoken)) return
    await ask(q, spoken)
  }

  const recognition = useSpeechRecognition({
    onFinal: (text) => {
      if (modeRef.current === 'voice') void submit(text, true)
      else setInput((prev) => (prev.trim() ? `${prev.trimEnd()} ${text}` : text)) // dictation: review, then send
    },
  })

  const toggleMic = () => {
    if (recognition.state === 'listening') {
      recognition.stop()
      return
    }
    tts.stop() // never listen to our own voice
    recognition.clearError()
    void recognition.start()
  }

  const setMode = (m: Mode) => {
    if (m === mode) return
    recognition.cancel() // switching never submits a half-heard sentence
    setModeState(m)
    try {
      localStorage.setItem(MODE_KEY, m)
    } catch {
      // not remembered
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
  const listening = recognition.state === 'listening'
  const count = messages?.length ?? 0

  const card = (i: CommandItem) => (
    <CommandCard key={i.id} item={i} voiceMode={mode === 'voice'}
      onDevice={(d) => patchItem(i.id, (x) => ({ ...x, deviceId: d }))}
      onConfirm={() => void confirm(i.id)} onCancel={() => cancelItem(i.id)} />
  )

  return (
    <Shell wide>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Assistant</h1>
            <p className="mt-1.5 text-sm text-slate-400">Ask about your home by typing or by voice.</p>
          </div>
          <div role="radiogroup" aria-label="Input mode" className="inline-flex rounded-xl bg-slate-800 p-1 text-sm">
            {(['text', 'voice'] as const).map((m) => (
              <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
                className={`rounded-lg px-5 py-1.5 font-medium transition-colors ${mode === m ? 'bg-indigo-400 text-slate-950' : 'text-slate-300 hover:text-slate-100'}`}>
                {m === 'text' ? 'Text' : 'Voice'}
              </button>
            ))}
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
                  <p className="text-lg font-semibold">{mode === 'voice' ? 'Tap the microphone and say, for example:' : 'What would you like to know?'}</p>
                  {mode !== 'voice' && <p className="mt-1 text-sm text-slate-400">Try one of these:</p>}
                  <ul className="mt-4 grid gap-3 sm:grid-cols-2">
                    {(mode === 'voice' ? VOICE_EXAMPLES : EXAMPLES).map((ex) => (
                      <li key={ex}>
                        <button type="button" onClick={() => void submit(ex, false)}
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
                  <ChatMessageView message={m} onSuggestionChange={changeSuggestion} />
                </Fragment>
              ))}
              {shownItems.filter((i) => i.afterIndex >= count).map(card)}
              {pending !== null && (
                <>
                  <div className="flex justify-end"><p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-slate-700 px-4 py-2.5 text-sm">{pending}</p></div>
                  <p className="flex items-center gap-2 text-sm text-slate-400" role="status">
                    <span aria-hidden="true" className="flex gap-1">
                      {[0, 1, 2].map((i) => <span key={i} className="h-1.5 w-1.5 animate-bounce rounded-full bg-indigo-400" style={{ animationDelay: `${i * 120}ms` }} />)}
                    </span>
                    Searching your records and thinking...
                  </p>
                </>
              )}
              <div ref={endRef} />
            </div>

            {mode === 'voice' && (
              <VoicePanel
                recognitionSupported={recognition.supported}
                listenState={recognition.state}
                transcript={recognition.interim}
                listenError={recognition.error}
                busy={busy}
                onMic={toggleMic}
                ttsSupported={tts.supported}
                speaking={tts.speaking}
                muted={tts.muted}
                onMuted={tts.setMuted}
                onStopSpeaking={tts.stop}
                voices={tts.voices}
                voiceURI={tts.voiceURI}
                onVoice={tts.setVoiceURI}
                lastAnswer={lastAnswer}
                onReplay={() => lastAnswer && tts.speak(lastAnswer.content)}
              />
            )}

            {mode === 'text' && tts.speaking && (
              <p className="flex items-center justify-between rounded bg-slate-900 px-3 py-2 text-sm text-slate-300" role="status">
                Speaking... <button type="button" onClick={tts.stop} className="rounded-md bg-slate-700 px-2 py-1 text-xs hover:bg-slate-600">Stop speaking</button>
              </p>
            )}

            {error && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}
            {mode === 'text' && recognition.error && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{recognition.error}</p>}

            <form onSubmit={(e) => { e.preventDefault(); void submit(input, false) }} className="flex items-end gap-2 border-t border-slate-700/60 pt-4">
              <label className="flex-1 text-xs text-slate-400">
                <span className="sr-only">Your question</span>
                <textarea id="assistant-input" aria-label="Your question" value={input} onChange={(e) => setInput(e.target.value)} maxLength={max} rows={mode === 'voice' ? 1 : 2}
                  placeholder={mode === 'voice' ? 'Or type here...' : listening ? 'Listening...' : 'Ask about your devices...'}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void submit(input, false) } }}
                  className="w-full resize-none rounded-2xl border border-slate-600 bg-slate-900 px-4 py-3 text-[15px] text-slate-100 placeholder:text-slate-500" />
                {mode === 'text' && listening && recognition.interim && (
                  <span className="block truncate text-slate-300" data-testid="dictation-preview">Hearing: “{recognition.interim}”</span>
                )}
                <span className="mt-0.5 block text-right">{input.length}/{max}</span>
              </label>
              {mode === 'text' && recognition.supported && (
                <button type="button" onClick={toggleMic} disabled={busy && !listening} aria-pressed={listening}
                  aria-label={listening ? 'Stop dictation' : 'Dictate with the microphone'}
                  className={`mb-5 rounded-xl px-4 py-3 text-sm font-medium disabled:opacity-60 ${listening ? 'mic-live bg-red-500/80 hover:bg-red-500' : 'btn-secondary'}`}>
                  {listening ? 'Stop' : 'Mic'}
                </button>
              )}
              <button type="submit" disabled={busy || input.trim() === ''}
                className="btn-primary mb-5 rounded-xl px-6 py-3 text-sm disabled:opacity-60">
                {busy ? 'Asking...' : mode === 'voice' ? 'Send' : 'Ask'}
              </button>
            </form>
          </section>
        </div>
    </Shell>
  )
}
