// The trend chart must plot readings in time order, keep line and area on the same points, and leave real gaps open.
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import TrendChart from './TrendChart'
import { segmentPoints, type TrendPoint } from './trendSegments'

const at = (min: number) => new Date(Date.UTC(2026, 10, 18, 10, min)).toISOString()

describe('segmentPoints', () => {
  it('sorts readings chronologically', () => {
    const { valid } = segmentPoints([{ at: at(2), value: 3 }, { at: at(0), value: 1 }, { at: at(1), value: 2 }])
    expect(valid.map((p) => p.value)).toEqual([1, 2, 3])
  })

  it('breaks the line at a missing reading and never turns it into zero', () => {
    const { valid, segments } = segmentPoints([
      { at: at(0), value: 20 }, { at: at(1), value: 21 }, { at: at(2), value: null }, { at: at(3), value: 22 }, { at: at(4), value: 23 },
    ])
    expect(segments.map((s) => s.map((p) => p.value))).toEqual([[20, 21], [22, 23]])
    expect(valid.some((p) => p.value === 0)).toBe(false)
  })

  it('does not bridge a long silence between readings', () => {
    const pts: TrendPoint[] = [0, 1, 2, 3, 90, 91, 92].map((m, i) => ({ at: at(m), value: 20 + i }))
    expect(segmentPoints(pts).segments).toHaveLength(2)
  })
})

// ESP-1, 15 Nov 2026 (IST): 30 s reports; a broker drop at 17:54:53 lost seq 61-62 (next 17:56:40), then a reboot left
// 17:59:39 -> 18:02:39 silent. Both are real silences; ordinary 23-31 s jitter must not split the line.
describe('segmentPoints on the ESP-1 readings around 6 PM', () => {
  const ist = (hms: string) => new Date(`2026-11-15T${hms}+05:30`).toISOString()
  const times = ['17:53:23', '17:53:53', '17:54:23', '17:54:53', '17:56:40', '17:57:09', '17:57:39', '17:58:09', '17:58:39', '17:59:09',
    '17:59:39', '18:02:39', '18:03:09', '18:03:41', '18:04:09']
  const pts: TrendPoint[] = times.map((s, i) => ({ at: ist(s), value: 30 + i / 10 }))

  it('keeps every reading and opens a gap only where readings never arrived', () => {
    const { valid, segments } = segmentPoints([...pts].reverse())   // the API returns newest first
    expect(valid).toHaveLength(times.length)
    expect(segments.map((s) => [s[0].at, s[s.length - 1].at])).toEqual([
      [ist('17:53:23'), ist('17:54:53')],
      [ist('17:56:40'), ist('17:59:39')],
      [ist('18:02:39'), ist('18:04:09')],
    ])
  })
})

describe('TrendChart', () => {
  it('ends the line and the area at the same, final reading', () => {
    const pts: TrendPoint[] = [0, 1, 2, 3, 4].map((m) => ({ at: at(m), value: 20 + m }))
    const { container } = render(<TrendChart points={pts} label="Temperature" unit="°C" />)
    const paths = [...container.querySelectorAll('svg g path')].map((p) => p.getAttribute('d') ?? '')
    const [area, line] = paths
    const lastLinePoint = line.trim().split(/[ML]/).filter(Boolean).pop()!.trim()
    expect(lastLinePoint).toBe('300.00 0.00')
    expect(area).toContain(`L${lastLinePoint} L300.00 100`)
    // The line is not drawn with a dash animation (which cut it short with a non-scaling stroke).
    expect(container.querySelector('svg g path[stroke]')?.getAttribute('stroke-dasharray')).toBeNull()
  })

  it('draws nothing with fewer than three real readings, ignoring nulls', () => {
    const { container } = render(<TrendChart points={[{ at: at(0), value: 1 }, { at: at(1), value: null }, { at: at(2), value: 2 }]} label="Humidity" unit="%" />)
    expect(container.querySelector('svg')).toBeNull()
  })
})
