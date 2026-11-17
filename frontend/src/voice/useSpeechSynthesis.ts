import { useCallback, useEffect, useRef, useState } from 'react'
import { chunkForSpeech, speakableText } from './speechText'

function synth(): SpeechSynthesis | null {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
    ? window.speechSynthesis
    : null
}

/** Read Aloud: speaks an assistant answer with the browser's default voice (no external service). */
export function useSpeechSynthesis() {
  const supported = synth() !== null
  const [speaking, setSpeaking] = useState(false)
  // Each speak() gets a new id; callbacks from an older, cancelled queue are ignored.
  const run = useRef(0)

  useEffect(() => {
    return () => synth()?.cancel() // leaving the page stops reading
  }, [])

  const stop = useCallback(() => {
    run.current++
    synth()?.cancel()
    setSpeaking(false)
  }, [])

  const speak = useCallback((content: string) => {
    const s = synth()
    if (!s) return
    run.current++
    const mine = run.current
    s.cancel() // never talk over the previous answer
    const chunks = chunkForSpeech(speakableText(content))
    if (chunks.length === 0) {
      setSpeaking(false)
      return
    }
    chunks.forEach((chunk, i) => {
      const u = new SpeechSynthesisUtterance(chunk)
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
  }, [])

  return { supported, speaking, speak, stop }
}
