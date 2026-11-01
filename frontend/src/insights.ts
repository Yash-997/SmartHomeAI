// Labels, tones and small calculations for the ML Insights page. No network, no React.
import type { AnomalyKind, AnomalySeverity, AnomalyStatus, MlMetrics, OccupancyState, OccupancyStatus } from './api'

export const STATE: Record<OccupancyState, { label: string; tone: string; hint: string }> = {
  NOT_TRAINED: { label: 'Not trained', tone: 'bg-slate-600 text-slate-100', hint: 'No model yet. Nothing is predicted until one is trained.' },
  INSUFFICIENT_DATA: { label: 'Not enough data', tone: 'bg-amber-500/20 text-amber-300', hint: 'The history is too short or too one-sided to learn from.' },
  READY: { label: 'Ready', tone: 'bg-green-500/20 text-green-300', hint: 'Trained, and better than guessing on data it had not seen.' },
  LOW_QUALITY: { label: 'Low quality', tone: 'bg-red-500/20 text-red-300', hint: 'Trained, but not clearly better than guessing. Treat predictions as unreliable.' },
}

export const SEVERITY: Record<AnomalySeverity, { label: string; tone: string }> = {
  CRITICAL: { label: 'Critical', tone: 'bg-red-500/20 text-red-300' },
  WARNING: { label: 'Warning', tone: 'bg-amber-500/20 text-amber-300' },
  INFO: { label: 'Info', tone: 'bg-sky-500/20 text-sky-300' },
}

export const ANOMALY_STATUS: Record<AnomalyStatus, string> = {
  OPEN: 'Open',
  ACKNOWLEDGED: 'Acknowledged',
  RESOLVED: 'Resolved',
  DISMISSED: 'Dismissed (false alarm)',
  AUTO_RESOLVED: 'Ended on its own',
}

export const KIND: Record<AnomalyKind, string> = {
  STATISTICAL_OUTLIER: 'Unusual reading',
  OUT_OF_RANGE: 'Impossible value',
  STUCK_VALUE: 'Possibly frozen sensor',
  MISSING_DATA: 'Sensor value missing',
  STALE_DATA: 'Device silent',
}

export const isActive = (s: AnomalyStatus) => s === 'OPEN' || s === 'ACKNOWLEDGED'

export const pct = (v: number, digits = 0) => `${(v * 100).toFixed(digits)}%`
export const num = (v: number | null, digits = 2) => (v === null ? 'n/a' : v.toFixed(digits))

/** What the training still needs, for a progress display. Returns null when there is nothing to track. */
export function progress(s: OccupancyStatus): { label: string; have: number; need: number }[] | null {
  if (!s.counts) return null
  const c = s.counts
  const r = s.requirements
  return [
    { label: `Usable ${r.slotMinutes}-minute slots`, have: c.usableSlots, need: r.minUsableSlots },
    { label: 'Calendar days covered', have: c.distinctDays, need: r.minDistinctDays },
    { label: 'Slots with motion', have: c.slotsWithMotion, need: r.minSlotsPerClass },
    { label: 'Slots without motion', have: c.slotsWithoutMotion, need: r.minSlotsPerClass },
  ]
}

/** One-line verdict on whether the model beat simply guessing the average. */
export function verdict(m: MlMetrics): { better: boolean; text: string } {
  const better = m.logLoss < m.baselineLogLoss
  return {
    better,
    text: better
      ? `Beats the "always guess the average" baseline (log loss ${m.logLoss.toFixed(3)} vs ${m.baselineLogLoss.toFixed(3)}).`
      : `Not better than the "always guess the average" baseline (log loss ${m.logLoss.toFixed(3)} vs ${m.baselineLogLoss.toFixed(3)}).`,
  }
}

export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

export function zoneList(): string[] {
  let zones: string[] = []
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    // older browsers: just the two we know
  }
  return Array.from(new Set(['UTC', browserZone(), ...zones]))
}

/** Plain-language reading of the details a finding carries (the numbers behind the explanation). */
export function detailPairs(details: Record<string, unknown> | null): [string, string][] {
  if (!details) return []
  const label: Record<string, string> = {
    value: 'Value seen',
    median: 'Typical value',
    spread: 'Typical spread',
    score: 'Score (robust z)',
    baselineSamples: 'Readings in baseline',
    timeOfDayBaseline: 'Time-of-day baseline',
    warnAt: 'Warning at |score|',
    criticalAt: 'Critical at |score|',
    identicalReadings: 'Identical readings',
    minutes: 'Minutes',
    missingMessages: 'Messages without a value',
    silentMinutes: 'Silent for (min)',
    lastReadingAt: 'Last reading',
    usableMin: 'Usable minimum',
    usableMax: 'Usable maximum',
    adcMaximum: 'ADC maximum',
    method: 'Method',
    handling: 'Handling',
  }
  return Object.entries(details)
    .filter(([k]) => k in label)
    .map(([k, v]) => [label[k], typeof v === 'number' ? String(Math.round(v * 100) / 100) : String(v)])
}
