import { useCallback, useEffect, useRef, useState } from 'react'
import { chunkForSpeech, speakableText } from './speechText'

const MUTE_KEY = 'smarthome.voice.muted'
const VOICE_KEY = 'smarthome.voice.voiceURI'

function readPref(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writePref(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // private mode or blocked storage: the preference just isn't remembered
  }
}

function synth(): SpeechSynthesis | null {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
    ? window.speechSynthesis
    : null
}

/** Speaks assistant answers with the browser's built-in voices (no external service). */
export function useSpeechSynthesis() {
  const supported = synth() !== null
  const [speaking, setSpeaking] = useState(false)
  const [muted, setMutedState] = useState(() => readPref(MUTE_KEY) === 'true')
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([])
  const [voiceURI, setVoiceURIState] = useState<string | null>(() => readPref(VOICE_KEY))
  // Each speak() gets a new id; callbacks from an older, cancelled queue are ignored.
  const run = useRef(0)

  useEffect(() => {
    const s = synth()
    if (!s) return
    const load = () => setVoices(s.getVoices())
    load()
    s.addEventListener?.('voiceschanged', load)
    return () => {
      s.removeEventListener?.('voiceschanged', load)
      s.cancel() // leaving the page stops the voice
    }
  }, [])

  const stop = useCallback(() => {
    run.current++
    synth()?.cancel()
    setSpeaking(false)
  }, [])

  const speak = useCallback(
    (content: string) => {
      const s = synth()
      if (!s || muted) return
      run.current++
      const mine = run.current
      s.cancel() // never talk over the previous answer
      const chunks = chunkForSpeech(speakableText(content))
      if (chunks.length === 0) {
        setSpeaking(false)
        return
      }
      const voice = voices.find((v) => v.voiceURI === voiceURI) ?? null
      chunks.forEach((chunk, i) => {
        const u = new SpeechSynthesisUtterance(chunk)
        if (voice) {
          u.voice = voice
          u.lang = voice.lang
        }
        const last = i === chunks.length - 1
        u.onend = () => {
          if (last && run.current === mine) setSpeaking(false)
        }
        u.onerror = () => {
          if (run.current === mine) setSpeaking(false)
        }
        s.speak(u)
      })
      setSpeaking(true)
    },
    [muted, voices, voiceURI],
  )

  const setMuted = useCallback(
    (m: boolean) => {
      setMutedState(m)
      writePref(MUTE_KEY, String(m))
      if (m) stop()
    },
    [stop],
  )

  const setVoiceURI = useCallback((uri: string | null) => {
    setVoiceURIState(uri)
    writePref(VOICE_KEY, uri)
  }, [])

  return { supported, speaking, muted, setMuted, voices, voiceURI, setVoiceURI, speak, stop }
}
