// Fake for the browser speech-synthesis API (Read aloud). jsdom has no speechSynthesis, so tests drive it by hand.
import { vi } from 'vitest'

export class FakeUtterance {
  text: string
  onend: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(text: string) {
    this.text = text
  }
}

export function installSynthesis() {
  const queue: FakeUtterance[] = []
  const synth = {
    queue,
    speak: vi.fn((u: FakeUtterance) => queue.push(u)),
    cancel: vi.fn(() => {
      queue.length = 0
    }),
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
