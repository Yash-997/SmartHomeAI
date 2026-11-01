import { useCallback, useEffect, useRef, useState } from 'react'

// Minimal typing for the Web Speech API (not in TypeScript's DOM library yet).
interface RecognitionAlternative { transcript: string }
interface RecognitionResult { isFinal: boolean; length: number; [i: number]: RecognitionAlternative }
interface RecognitionResultEvent { resultIndex: number; results: { length: number; [i: number]: RecognitionResult } }
interface RecognitionErrorEvent { error: string }
export interface Recognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  onresult: ((e: RecognitionResultEvent) => void) | null
  onerror: ((e: RecognitionErrorEvent) => void) | null
  onend: (() => void) | null
  onspeechstart?: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}
type RecognitionCtor = new () => Recognition

export function recognitionCtor(): RecognitionCtor | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

export type ListenState = 'idle' | 'listening' | 'error'

const ERRORS: Record<string, string> = {
  'not-allowed': 'Microphone access is blocked. Allow the microphone for this site (the icon in the address bar), then try again.',
  'service-not-allowed': 'This browser does not allow speech recognition here. Use Chrome or Edge on localhost or HTTPS, or type instead.',
  'no-speech': "I didn't hear anything. Tap the microphone and speak again.",
  'audio-capture': 'No microphone was found. Check that one is connected and not used by another app.',
  network: "Speech recognition could not reach the browser's speech service. Check the internet connection, or type instead.",
  'language-not-supported': 'Speech recognition does not support this language in this browser.',
}

export interface RecognitionOptions {
  /** Called once per listening session with the final transcript. Never called after cancel() or an error. */
  onFinal: (text: string) => void
  lang?: string
  /** Stop when nothing at all was heard for this long. */
  noSpeechMs?: number
  /** Stop this long after the last words were heard. */
  silenceMs?: number
  /** Hard limit for one session: the microphone is never left listening. */
  maxMs?: number
}

export function useSpeechRecognition({ onFinal, lang, noSpeechMs = 8000, silenceMs = 2500, maxMs = 20000 }: RecognitionOptions) {
  const supported = recognitionCtor() !== null
  const [state, setState] = useState<ListenState>('idle')
  const [interim, setInterim] = useState('')
  const [error, setError] = useState('')

  const recRef = useRef<Recognition | null>(null)
  const timers = useRef<{ idle?: ReturnType<typeof setTimeout>; max?: ReturnType<typeof setTimeout> }>({})
  const session = useRef({ final: '', interim: '', delivered: false, failed: false, cancelled: false })
  const onFinalRef = useRef(onFinal)
  useEffect(() => {
    onFinalRef.current = onFinal
  }, [onFinal])

  const clearTimers = () => {
    clearTimeout(timers.current.idle)
    clearTimeout(timers.current.max)
  }

  const armIdle = (ms: number) => {
    clearTimeout(timers.current.idle)
    timers.current.idle = setTimeout(() => recRef.current?.stop(), ms)
  }

  const stop = useCallback(() => recRef.current?.stop(), [])

  /** Stops without submitting anything that was heard. */
  const cancel = useCallback(() => {
    session.current.cancelled = true
    clearTimers()
    recRef.current?.abort()
    recRef.current = null
    setInterim('')
    setState('idle')
  }, [])

  const start = useCallback(async () => {
    if (recRef.current) return // already listening: one session at a time
    const Ctor = recognitionCtor()
    if (!Ctor) {
      setError('Voice input is not supported in this browser. Use Chrome or Edge, or type your question.')
      setState('error')
      return
    }
    try {
      const perm = await navigator.permissions?.query({ name: 'microphone' as PermissionName })
      if (perm?.state === 'denied') {
        setError(ERRORS['not-allowed'])
        setState('error')
        return
      }
    } catch {
      // The Permissions API does not know "microphone" in every browser; the recogniser will ask instead.
    }

    const rec = new Ctor()
    rec.lang = lang || navigator.language || 'en-US'
    rec.continuous = true
    rec.interimResults = true
    rec.maxAlternatives = 1
    const s = { final: '', interim: '', delivered: false, failed: false, cancelled: false }
    session.current = s
    const current = () => session.current === s

    rec.onresult = (e) => {
      if (!current() || s.cancelled) return
      let finalText = ''
      let interimText = ''
      for (let i = 0; i < e.results.length; i++) {
        const r = e.results[i]
        if (r.isFinal) finalText += r[0].transcript
        else interimText += r[0].transcript
      }
      s.final = finalText
      s.interim = interimText
      setInterim(`${finalText}${interimText}`.trim())
      armIdle(silenceMs)
    }
    rec.onerror = (e) => {
      if (e.error === 'aborted' || !current() || s.cancelled) return
      s.failed = true
      setError(ERRORS[e.error] ?? 'Speech recognition failed. Try again, or type your question.')
      setState('error')
    }
    rec.onend = () => {
      if (recRef.current === rec) recRef.current = null
      if (!current()) return
      clearTimers()
      const text = (s.final || s.interim).trim()
      if (!s.failed) setState('idle')
      if (!s.failed && !s.cancelled && !s.delivered) {
        s.delivered = true
        if (text) onFinalRef.current(text)
        else {
          setError(ERRORS['no-speech'])
          setState('error')
        }
      }
    }

    setError('')
    setInterim('')
    recRef.current = rec
    try {
      rec.start()
    } catch {
      recRef.current = null
      setError('Could not start the microphone. Try again.')
      setState('error')
      return
    }
    setState('listening')
    armIdle(noSpeechMs)
    timers.current.max = setTimeout(() => recRef.current?.stop(), maxMs)
  }, [lang, noSpeechMs, silenceMs, maxMs])

  useEffect(
    () => () => {
      session.current.cancelled = true
      clearTimers()
      recRef.current?.abort()
    },
    [],
  )

  const clearError = useCallback(() => {
    setError('')
    setState((s) => (s === 'error' ? 'idle' : s))
  }, [])

  return { supported, state, interim, error, start, stop, cancel, clearError }
}
