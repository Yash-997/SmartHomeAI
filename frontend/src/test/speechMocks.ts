// Fakes for the browser speech APIs. jsdom has neither SpeechRecognition nor speechSynthesis, so tests drive these
// by hand: `hear()` plays the part of the user speaking, `fail()` the part of the browser reporting an error.
import { vi } from 'vitest'

type Handler<E> = ((e: E) => void) | null

export class FakeRecognition {
  static instances: FakeRecognition[] = []
  lang = ''
  continuous = false
  interimResults = false
  maxAlternatives = 1
  onresult: Handler<unknown> = null
  onerror: Handler<{ error: string }> = null
  onend: (() => void) | null = null
  started = false
  stopped = false
  aborted = false
  private finals: string[] = []

  constructor() {
    FakeRecognition.instances.push(this)
  }

  static latest(): FakeRecognition {
    return FakeRecognition.instances[FakeRecognition.instances.length - 1]
  }

  start() {
    this.started = true
  }

  stop() {
    this.stopped = true
    this.onend?.()
  }

  abort() {
    this.aborted = true
    this.onend?.()
  }

  /** The user said `text`. Interim results are shown live; final ones are what gets submitted. */
  hear(text: string, isFinal = true) {
    const results = [...this.finals.map((t) => this.result(t, true)), this.result(text, isFinal)]
    if (isFinal) this.finals.push(text)
    this.onresult?.({ resultIndex: results.length - 1, results })
  }

  fail(error: string) {
    this.onerror?.({ error })
    this.onend?.()
  }

  /** The browser ended the session by itself (for example after a pause). */
  end() {
    this.onend?.()
  }

  private result(transcript: string, isFinal: boolean) {
    return Object.assign([{ transcript }], { isFinal })
  }
}

export class FakeUtterance {
  text: string
  voice: unknown = null
  lang = ''
  onend: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(text: string) {
    this.text = text
  }
}

export function installRecognition() {
  FakeRecognition.instances = []
  Object.defineProperty(window, 'SpeechRecognition', { value: FakeRecognition, configurable: true, writable: true })
}

export function removeRecognition() {
  delete (window as unknown as Record<string, unknown>).SpeechRecognition
  delete (window as unknown as Record<string, unknown>).webkitSpeechRecognition
}

export function installSynthesis() {
  const queue: FakeUtterance[] = []
  const synth = {
    queue,
    speak: vi.fn((u: FakeUtterance) => queue.push(u)),
    cancel: vi.fn(() => {
      queue.length = 0
    }),
    getVoices: () => [{ name: 'Test English', voiceURI: 'test-en', lang: 'en-US' }],
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    /** The browser finished reading everything that was queued. */
    finish() {
      const items = [...queue]
      queue.length = 0
      items.forEach((u) => u.onend?.())
    },
    spokenText: () => queue.map((u) => u.text).join(' '),
  }
  Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true, writable: true })
  Object.defineProperty(globalThis, 'SpeechSynthesisUtterance', { value: FakeUtterance, configurable: true, writable: true })
  return synth
}

export function removeSynthesis() {
  delete (window as unknown as Record<string, unknown>).speechSynthesis
  delete (globalThis as unknown as Record<string, unknown>).SpeechSynthesisUtterance
}

export function denyMicrophonePermission() {
  Object.defineProperty(navigator, 'permissions', {
    value: { query: vi.fn(async () => ({ state: 'denied' })) },
    configurable: true,
  })
}

export function resetPermissions() {
  Object.defineProperty(navigator, 'permissions', { value: undefined, configurable: true })
}
