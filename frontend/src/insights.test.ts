import { describe, expect, it } from 'vitest'
import { DISPLAY_TZ, KIND, detailPairs, fmtDate, fmtDateTime } from './insights'

describe('ML insights helpers', () => {
  it('shows times in Asia/Kolkata, whatever the browser zone', () => {
    expect(DISPLAY_TZ).toBe('Asia/Kolkata')
    // 08:42 UTC is 14:12 in India (UTC+05:30)
    expect(fmtDateTime('2026-11-11T08:42:00Z')).toContain('14:12')
    expect(fmtDate('2026-11-10T20:00:00Z')).toContain('11 Nov 2026') // already the next day in India
    expect(fmtDateTime(null)).toBe('never')
  })






  it('names the new anomaly kinds and their evidence in plain words', () => {
    expect(KIND.SUDDEN_CHANGE).toBe('Sudden change')
    expect(KIND.UNUSUAL_ACTIVITY).toBe('Motion at a normally quiet time')
    const pairs = detailPairs({ previous: 24, value: 31.5, change: 7.5, seconds: 30, slotsAtThisHour: 12, internal: 'hidden' })
    expect(pairs).toContainEqual(['Previous reading', '24'])
    expect(pairs).toContainEqual(['Change', '7.5'])
    expect(pairs).toContainEqual(['Past slots at this hour', '12'])
    expect(pairs.find(([k]) => k === 'internal')).toBeUndefined()
  })
})
