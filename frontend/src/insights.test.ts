import { describe, expect, it } from 'vitest'
import type { OccupancyPattern, OccupancyStatus, PatternCell } from './api'
import { DISPLAY_TZ, KIND, STATE, detailPairs, fmtDate, fmtDateTime, patternGrid, patternSummary, progress, zoneList } from './insights'

function status(dataset: Partial<OccupancyStatus['data']['dataset']> = {}): OccupancyStatus {
  return {
    state: 'NOT_TRAINED',
    predictionsAvailable: false,
    message: 'Collecting historical data.',
    trainedAt: null,
    timeZone: null,
    counts: null,
    requirements: { slotMinutes: 15, minReadingsPerSlot: 10, minUsableSlots: 200, minDistinctDays: 3, minSlotsPerClass: 20 },
    metrics: null,
    data: {
      totalRecords: 1234,
      collectionStartedAt: '2026-11-01T00:00:00Z',
      recordsLast24h: 2880,
      motionReadings: 1200,
      firstAt: '2026-11-01T00:00:00Z',
      lastAt: '2026-11-11T08:00:00Z',
      dataset: {
        usableSlots: 120, slotsWithMotion: 30, slotsWithoutMotion: 90, sparseSlotsIgnored: 4, distinctDays: 2, enoughForTraining: false,
        checkedAt: '2026-11-11T08:00:00Z', ...dataset,
      },
    },
    method: 'Logistic regression',
    collecting: true,
    modelVersion: 0,
    lastSuccessfulTrainingAt: null,
    trainingRuns: 0,
    featureSet: 'v2',
    autoTraining: true,
  }
}

function pattern(prob: (day: number, hour: number) => number, slots = 4): OccupancyPattern {
  const cells: PatternCell[] = []
  for (let d = 1; d <= 7; d++) for (let h = 0; h < 24; h++) cells.push({ dayOfWeek: d, hour: h, slots, observedRate: prob(d, h), modelProbability: prob(d, h) })
  return { timeZone: 'Asia/Kolkata', modelVersion: 3, quality: 'READY', trainedAt: '2026-11-11T00:00:00Z', overallRate: 0.3, cells }
}

describe('ML insights helpers', () => {
  it('shows times in Asia/Kolkata, whatever the browser zone', () => {
    expect(DISPLAY_TZ).toBe('Asia/Kolkata')
    // 08:42 UTC is 14:12 in India (UTC+05:30)
    expect(fmtDateTime('2026-11-11T08:42:00Z')).toContain('14:12')
    expect(fmtDate('2026-11-10T20:00:00Z')).toContain('11 Nov 2026') // already the next day in India
    expect(fmtDateTime(null)).toBe('never')
    expect(zoneList()[0]).toBe('Asia/Kolkata')
  })

  it('says "Collecting historical data" until a model exists', () => {
    expect(STATE.NOT_TRAINED.label).toBe('Collecting historical data')
    expect(STATE.INSUFFICIENT_DATA.label).toBe('Collecting historical data')
    expect(STATE.READY.label).toBe('Ready')
  })

  it('tracks readiness from the live dataset, not from an old training run', () => {
    const steps = progress(status())
    expect(steps?.map((s) => [s.have, s.need])).toEqual([[120, 200], [2, 3], [30, 20], [90, 20]])
    expect(progress(status({ usableSlots: 500 }))?.[0].have).toBe(500)
    const unknown = status()
    unknown.data.dataset = null
    expect(progress(unknown)).toBeNull()   // nothing known: no invented progress
  })

  it('lays the learned week out as 7 x 24 and leaves missing hours empty', () => {
    const p = pattern(() => 0.5)
    p.cells = p.cells.filter((c) => !(c.dayOfWeek === 7 && c.hour === 23))
    const grid = patternGrid(p)
    expect(grid).toHaveLength(7)
    expect(grid[0]).toHaveLength(24)
    expect(grid[0][0]?.hour).toBe(0)
    expect(grid[6][23]).toBeNull()
  })

  it('summarises busy and quiet hours separately for weekdays and weekends', () => {
    // Weekdays busy 7-9, weekends busy 11-13; nights quiet.
    const s = patternSummary(pattern((d, h) => (d <= 5 ? (h >= 7 && h <= 9 ? 0.9 : h < 5 ? 0.02 : 0.3) : h >= 11 && h <= 13 ? 0.8 : h < 5 ? 0.01 : 0.3)))
    expect(s.weekdayBusy).toEqual([7, 8, 9])
    expect(s.weekendBusy).toEqual([11, 12, 13])
    expect(s.weekdayQuiet.every((h) => h < 5)).toBe(true)
    expect(s.weekendQuiet.every((h) => h < 5)).toBe(true)
  })

  it('ignores hours without training data in the summary', () => {
    const s = patternSummary(pattern(() => 0.5, 0))
    expect(s.weekdayBusy).toEqual([])
    expect(s.weekendQuiet).toEqual([])
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
