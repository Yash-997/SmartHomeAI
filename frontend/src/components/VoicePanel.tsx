import type { ChatMessage } from '../api'
import type { ListenState } from '../voice/useSpeechRecognition'
import { speakableText } from '../voice/speechText'
import { CheckIcon } from '../ui/icons'
import Waveform from './Waveform'

interface Props {
  recognitionSupported: boolean
  listenState: ListenState
  transcript: string
  listenError: string
  busy: boolean
  onMic: () => void
  ttsSupported: boolean
  speaking: boolean
  muted: boolean
  onMuted: (m: boolean) => void
  onStopSpeaking: () => void
  voices: SpeechSynthesisVoice[]
  voiceURI: string | null
  onVoice: (uri: string | null) => void
  lastAnswer: ChatMessage | null
  onReplay: () => void
}

type Phase = 'unsupported' | 'listening' | 'processing' | 'speaking' | 'error' | 'done' | 'idle'

const CHIP: Record<Phase, { text: string; tone: string }> = {
  unsupported: { text: 'Unavailable', tone: 'bg-slate-700 text-slate-300' },
  listening: { text: 'Listening', tone: 'bg-indigo-400/20 text-indigo-300' },
  processing: { text: 'Processing', tone: 'bg-sky-400/15 text-sky-300' },
  speaking: { text: 'Speaking', tone: 'bg-sky-400/15 text-sky-300' },
  error: { text: 'Voice failed', tone: 'bg-red-500/15 text-red-300' },
  done: { text: 'Answered', tone: 'bg-green-500/15 text-green-300' },
  idle: { text: 'Ready', tone: 'bg-slate-700 text-slate-300' },
}

/** Compact voice bar: one mic button, a status line and a short waveform. Typing below always remains available. */
export default function VoicePanel(p: Props) {
  const listening = p.listenState === 'listening'
  const phase: Phase = !p.recognitionSupported
    ? 'unsupported'
    : listening
      ? 'listening'
      : p.busy
        ? 'processing'
        : p.speaking
          ? 'speaking'
          : p.listenState === 'error'
            ? 'error'
            : p.lastAnswer
              ? 'done'
              : 'idle'

  const label: Record<Phase, string> = {
    unsupported: 'Voice input is not supported in this browser. Use Chrome or Edge, or type below.',
    listening: 'Listening... tap again when you are done',
    processing: 'Working on it...',
    speaking: 'Speaking',
    error: p.listenError,
    done: 'Answer ready. Tap the microphone to ask another question.',
    idle: 'Tap the microphone and ask a question or give a command',
  }

  const english = p.voices.filter((v) => v.lang.toLowerCase().startsWith((navigator.language || 'en').slice(0, 2).toLowerCase()))
  const voiceList = english.length > 0 ? english : p.voices
  const chip = CHIP[phase]

  return (
    <div className="rounded-2xl bg-slate-900/60 p-3 sm:p-4" aria-label="Voice assistant">
      <div className="flex items-center gap-3 sm:gap-4">
        <button
          type="button"
          onClick={p.onMic}
          disabled={!p.recognitionSupported || (p.busy && !listening)}
          aria-pressed={listening}
          aria-label={listening ? 'Stop listening' : 'Start listening'}
          className={`grid h-14 w-14 shrink-0 place-items-center rounded-full text-slate-900 shadow-md transition disabled:cursor-not-allowed disabled:opacity-40 ${
            listening ? 'mic-live bg-red-400 hover:bg-red-300' : 'bg-indigo-400 hover:bg-indigo-300'
          }`}
        >
          {listening ? (
            <svg viewBox="0 0 24 24" className="h-6 w-6" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" /></svg>
          ) : (
            <svg viewBox="0 0 24 24" className="h-7 w-7" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor" />
              <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
            </svg>
          )}
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span aria-hidden="true" className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${chip.tone}`}>
              {phase === 'done' && <CheckIcon width={12} height={12} />}{chip.text}
            </span>
            {phase === 'processing' && <span aria-hidden="true" className="h-3 w-3 animate-spin rounded-full border-2 border-sky-300 border-t-transparent" />}
          </div>
          <p role="status" className={`mt-1 text-sm ${phase === 'error' ? 'text-red-300' : 'text-slate-300'}`} data-testid="voice-status">
            {label[phase]}
          </p>
        </div>

        <div className="hidden w-28 shrink-0 sm:block">
          <Waveform active={listening || p.speaking} tone={listening ? 'listen' : 'speak'} />
        </div>
      </div>

      {(listening || p.transcript) && (
        <p className="mt-3 min-h-[1.5rem] rounded-xl bg-slate-800/80 px-3 py-2 text-[15px] text-slate-100" data-testid="live-transcript">
          {p.transcript ? `“${p.transcript}”` : '...'}
        </p>
      )}

      {phase === 'error' && p.recognitionSupported && (
        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl bg-red-500/10 px-3 py-2">
          <span className="min-w-0 flex-1 text-xs text-slate-300">Voice input did not work, and nothing was sent. You can retry or type your question below.</span>
          <button type="button" onClick={p.onMic} className="btn-primary rounded-lg px-3.5 py-1.5 text-xs">Try again</button>
          <button type="button" onClick={() => document.getElementById('assistant-input')?.focus()} className="btn-secondary rounded-lg px-3.5 py-1.5 text-xs">Type instead</button>
        </div>
      )}

      {p.lastAnswer && (
        <div className="mt-3 rounded-xl bg-slate-800/80 p-3 text-sm" aria-label="Latest answer">
          <div className="flex items-center justify-between gap-2">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">Latest answer</p>
            {p.ttsSupported && !p.speaking && !p.muted && (
              <button type="button" onClick={p.onReplay} className="text-xs text-indigo-300 hover:underline">Read aloud again</button>
            )}
          </div>
          <p className="mt-1 whitespace-pre-wrap text-slate-100">{speakableText(p.lastAnswer.content)}</p>
          {p.lastAnswer.sources.length > 0 && (
            <p className="mt-1 text-xs text-slate-500">{p.lastAnswer.sources.length} source(s): see the conversation above.</p>
          )}
        </div>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
        {p.ttsSupported ? (
          <>
            <button type="button" onClick={p.onStopSpeaking} disabled={!p.speaking} className="btn-secondary rounded-lg px-3 py-1.5 text-xs disabled:opacity-40">
              Stop speaking
            </button>
            <button type="button" onClick={() => p.onMuted(!p.muted)} aria-pressed={p.muted} className="btn-secondary rounded-lg px-3 py-1.5 text-xs">
              {p.muted ? 'Unmute' : 'Mute'}
            </button>
            {voiceList.length > 0 && (
              <label className="flex items-center gap-1 text-xs text-slate-400">
                Voice
                <select value={p.voiceURI ?? ''} onChange={(e) => p.onVoice(e.target.value || null)}
                  className="max-w-[10rem] rounded-lg border border-slate-600 bg-slate-900 px-2 py-1 text-slate-100">
                  <option value="">Browser default</option>
                  {voiceList.map((v) => <option key={v.voiceURI} value={v.voiceURI}>{v.name}</option>)}
                </select>
              </label>
            )}
          </>
        ) : (
          <p className="text-xs text-slate-500">This browser cannot read answers aloud; they are shown as text.</p>
        )}
        <details className="ml-auto text-[11px] text-slate-500">
          <summary className="cursor-pointer hover:text-slate-300">Privacy</summary>
          <p className="mt-1 max-w-xs">
            The microphone only listens after you tap it and stops by itself after a short pause. In Chrome and Edge, speech is
            transcribed by the browser&apos;s own online speech service.
          </p>
        </details>
      </div>
    </div>
  )
}
