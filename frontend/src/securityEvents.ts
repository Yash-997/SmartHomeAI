import type { SecurityEvent } from './api'

const sentence = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase()

/** Short headline for a security event, e.g. "Motion detected while Away". */
export function eventTitle(e: Pick<SecurityEvent, 'kind' | 'mode'>): string {
  switch (e.kind) {
    case 'MOTION_ALERT': return `Motion detected while ${sentence(e.mode)}`
    case 'DEVICE_OFFLINE': return `Device offline while ${sentence(e.mode)}`
    default: return sentence(e.kind.replace(/_/g, ' '))
  }
}

/** "5 min ago", "2 h ago", or the date for anything older than a day. */
export function relativeTime(iso: string, now = Date.now()): string {
  const s = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString()
}
