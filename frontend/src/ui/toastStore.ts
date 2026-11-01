import { useSyncExternalStore } from 'react'

export type ToastKind = 'success' | 'error'
export interface ToastItem {
  id: number
  kind: ToastKind
  text: string
}

let items: ToastItem[] = []
let nextId = 1
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((l) => l())
}

export function dismissToast(id: number) {
  items = items.filter((t) => t.id !== id)
  emit()
}

/** Shows a short message in the corner. Success fades after 4 s, errors after 7 s. Safe to call from anywhere. */
export function notify(text: string, kind: ToastKind = 'success') {
  const id = nextId++
  items = [...items.slice(-3), { id, kind, text }]
  emit()
  window.setTimeout(() => dismissToast(id), kind === 'error' ? 7000 : 4000)
}

export function useToasts(): ToastItem[] {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => items,
  )
}
