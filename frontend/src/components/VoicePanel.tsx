import type { ChatMessage } from '../api'
import type { ListenState } from '../voice/useSpeechRecognition'
import { speakableText } from '../voice/speechText'
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

export default function VoicePanel(p: Props) {
  const listening = p.listenState === 'listening'
  const phase = !p.recognitionSupported
    ? 'unsupported'
    : listening
      ? 'listening'
      : p.busy
        ? 'processing'
        : p.speaking
          ? 'speaking'
          : p.listenState === 'error'
            ? 'error'
            : 'idle'

  const label: Record<typeof phase, string> = {
    unsupported: 'Voice input is not supported in this browser. Use Chrome or Edge, or type below.',
    listening: 'Listening... tap again when you are done',
    processing: 'Working on it...',
    speaking: 'Speaking',
    error: p.listenError,
    idle: 'Tap the microphone and ask a question or give a command',
  }

  const english = p.voices.filter((v) => v.lang.toLowerCase().startsWith((navigator.language || 'en').slice(0, 2).toLowerCase()))
  const voiceList = english.length > 0 ? english : p.voices

  return (
    <div className="rounded-xl border border-slate-700 bg-slate-900/70 p-4" aria-label="Voice assistant">
      <div className="flex flex-col items-center gap-3 text-center">
        <button
          type="button"
          onClick={p.onMic}
          disabled={!p.recognitionSupported || (p.busy && !listening)}
          aria-pressed={listening}
          aria-label={listening ? 'Stop listening' : 'Start listening'}
          className={`grid h-20 w-20 place-items-center rounded-full text-slate-900 shadow-lg transition disabled:cursor-not-allowed disabled:opacity-40 ${
            listening ? 'mic-live bg-red-400 hover:bg-red-300' : 'bg-indigo-400 hover:bg-indigo-300'
          }`}
        >
          {listening ? (
            <svg viewBox="0 0 24 24" className="h-8 w-8" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" /></svg>
          ) : (
            <svg viewBox="0 0 24 24" className="h-9 w-9" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor" />
              <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
            </svg>
          )}
        </button>

        <Waveform active={listening || p.speaking} tone={listening ? 'listen' : 'speak'} />

        <p role="status" className={`text-sm ${phase === 'error' ? 'text-red-300' : 'text-slate-300'}`} data-testid="voice-status">
          {label[phase]}
        </p>

        {(listening || p.transcript) && (
          <p className="min-h-[1.5rem] max-w-prose text-base text-slate-100" data-testid="live-transcript">
            {p.transcript ? `“${p.transcript}”` : '...'}
          </p>
        )}
      </div>

      {p.lastAnswer && (
        <div className="mt-4 rounded-lg bg-slate-800 p-3 text-sm" aria-label="Latest answer">
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs font-medium uppercase tracking-wide text-slate-400">Latest answer</p>
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

      <div className="mt-4 flex flex-wrap items-center justify-center gap-2 text-sm">
        {p.ttsSupported ? (
          <>
            <button type="button" onClick={p.onStopSpeaking} disabled={!p.speaking}
              className="rounded-md bg-slate-700 px-3 py-1.5 hover:bg-slate-600 disabled:opacity-40">
              Stop speaking
            </button>
            <button type="button" onClick={() => p.onMuted(!p.muted)} aria-pressed={p.muted}
              className="rounded-md bg-slate-700 px-3 py-1.5 hover:bg-slate-600">
              {p.muted ? 'Unmute' : 'Mute'}
            </button>
            {voiceList.length > 0 && (
              <label className="flex items-center gap-1 text-xs text-slate-400">
                Voice
                <select value={p.voiceURI ?? ''} onChange={(e) => p.onVoice(e.target.value || null)}
                  className="max-w-[12rem] rounded-md border border-slate-600 bg-slate-900 px-2 py-1 text-slate-100">
                  <option value="">Browser default</option>
                  {voiceList.map((v) => <option key={v.voiceURI} value={v.voiceURI}>{v.name}</option>)}
                </select>
              </label>
            )}
          </>
        ) : (
          <p className="text-xs text-slate-500">This browser cannot read answers aloud; they are shown as text.</p>
        )}
      </div>
      <p className="mt-3 text-center text-[11px] text-slate-500">
        The microphone only listens after you tap it and stops by itself after a short pause. In Chrome and Edge, speech is
        transcribed by the browser's own online speech service.
      </p>
    </div>
  )
}
