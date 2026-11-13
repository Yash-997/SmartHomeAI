import { describe, expect, it } from 'vitest'
import { eventTitle, relativeTime } from './securityEvents'

describe('eventTitle', () => {
  it('names motion and offline alerts with the mode they happened in', () => {
    expect(eventTitle({ kind: 'MOTION_ALERT', mode: 'AWAY' })).toBe('Motion detected while Away')
    expect(eventTitle({ kind: 'DEVICE_OFFLINE', mode: 'NIGHT' })).toBe('Device offline while Night')
  })

  it('turns other kinds into plain words', () => {
    expect(eventTitle({ kind: 'MODE_CHANGED', mode: 'HOME' })).toBe('Mode changed')
  })
})

describe('relativeTime', () => {
  const now = new Date('2026-11-12T12:00:00Z').getTime()
  it('is short for recent times', () => {
    expect(relativeTime('2026-11-12T11:59:40Z', now)).toBe('just now')
    expect(relativeTime('2026-11-12T11:55:00Z', now)).toBe('5 min ago')
    expect(relativeTime('2026-11-12T09:00:00Z', now)).toBe('3 h ago')
  })
})
