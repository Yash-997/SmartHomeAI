export interface TrendPoint {
  /** ISO timestamp of the reading. */
  at: string
  /** The measured value, or null when the reading carried no value for this sensor (a gap, never zero). */
  value: number | null
}

export type Valid = { t: number; at: string; value: number }

/**
 * Splits the readings into runs that may be joined by a line. A run ends at a reading with no value, or where the time
 * between two readings is far longer than the usual reporting interval (the device was silent): those gaps are left
 * open instead of being bridged by a line.
 */
export function segmentPoints(points: TrendPoint[]): { valid: Valid[]; segments: Valid[][] } {
  const sorted = points
    .map((p) => ({ t: Date.parse(p.at), at: p.at, value: p.value }))
    .filter((p) => Number.isFinite(p.t))
    .sort((a, b) => a.t - b.t)

  const deltas: number[] = []
  for (let i = 1; i < sorted.length; i++) deltas.push(sorted[i].t - sorted[i - 1].t)
  const median = deltas.length ? [...deltas].sort((a, b) => a - b)[Math.floor(deltas.length / 2)] : 0
  // A silence of 2.5 usual intervals means at least one expected reading never arrived (on ESP-1 every normal interval is
  // 15-31 s and every longer one coincides with a reboot or a dropped broker connection), so it is shown as a gap. The
  // 60 s floor keeps fast-reporting devices from splitting on ordinary network jitter.
  const maxGap = median > 0 ? Math.max(median * 2.5, 60_000) : Infinity

  const segments: Valid[][] = []
  let run: Valid[] = []
  let prevT: number | null = null
  for (const p of sorted) {
    const missing = p.value === null || p.value === undefined || !Number.isFinite(p.value)
    if (missing || (prevT !== null && p.t - prevT > maxGap)) {
      if (run.length) segments.push(run)
      run = []
    }
    if (!missing) run.push({ t: p.t, at: p.at, value: p.value as number })
    prevT = p.t
  }
  if (run.length) segments.push(run)
  return { valid: segments.flat(), segments }
}

