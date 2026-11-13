// Labels, tones and small calculations for the ML Insights page. No network, no React.
import type { AnomalyKind, AnomalySeverity, AnomalyStatus, MlDataSummary, MlMetrics, MlStatus, OccupancyPattern, OccupancyState, OccupancyStatus, PatternCell } from './api'

/** Times on the ML Insights page are shown in the home's time zone. */
export const DISPLAY_TZ = 'Asia/Kolkata'

const DATE_TIME = new Intl.DateTimeFormat('en-IN', { timeZone: DISPLAY_TZ, day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
const DATE = new Intl.DateTimeFormat('en-IN', { timeZone: DISPLAY_TZ, day: 'numeric', month: 'short', year: 'numeric' })

/** "11 Nov 2026, 14:05" in Asia/Kolkata, or "never" for null. */
export function fmtDateTime(iso: string | null | undefined): string {
  return iso ? DATE_TIME.format(new Date(iso)) : 'never'
}

export function fmtDate(iso: string | null | undefined): string {
  return iso ? DATE.format(new Date(iso)) : '-'
}

export const fmtCount = (n: number | null | undefined) => (n === null || n === undefined ? '-' : n.toLocaleString('en-IN'))

/**
 * Makes a status response safe to render, whatever backend version sent it. Fields missing from an older or partial
 * response become "unknown" (null / 0 runs / no model), never invented values, so the page always renders and simply
 * shows "Collecting historical data" or "-" where it cannot know.
 */
export function normalizeStatus(raw: MlStatus): MlStatus {
  const o = (raw.occupancy ?? {}) as Partial<OccupancyStatus>
  const d = (o.data ?? {}) as Partial<MlDataSummary>
  const occupancy: OccupancyStatus = {
    state: o.state ?? 'NOT_TRAINED',
    predictionsAvailable: o.predictionsAvailable ?? false,
    message: o.message ?? 'Collecting historical data.',
    trainedAt: o.trainedAt ?? null,
    timeZone: o.timeZone ?? null,
    counts: o.counts ?? null,
    requirements: o.requirements ?? { slotMinutes: 15, minReadingsPerSlot: 10, minUsableSlots: 200, minDistinctDays: 3, minSlotsPerClass: 20 },
    metrics: o.metrics ?? null,
    data: {
      totalRecords: d.totalRecords ?? null,
      collectionStartedAt: d.collectionStartedAt ?? null,
      recordsLast24h: d.recordsLast24h ?? null,
      motionReadings: d.motionReadings ?? 0,
      firstAt: d.firstAt ?? null,
      lastAt: d.lastAt ?? null,
      dataset: d.dataset ?? null,
    },
    method: o.method ?? '',
    collecting: o.collecting ?? !(o.predictionsAvailable ?? false),
    modelVersion: o.modelVersion ?? 0,
    lastSuccessfulTrainingAt: o.lastSuccessfulTrainingAt ?? null,
    trainingRuns: o.trainingRuns ?? 0,
    featureSet: o.featureSet ?? '',
    autoTraining: o.autoTraining ?? true,
  }
  return {
    occupancy,
    anomalies: raw.anomalies ?? { active: 0, critical: 0, warning: 0, info: 0, lastScanAt: null },
    notice: raw.notice ?? '',
  }
}

export const STATE: Record<OccupancyState, { label: string; tone: string; hint: string }> = {
  NOT_TRAINED: { label: 'Collecting historical data', tone: 'bg-slate-600 text-slate-100', hint: 'No model yet. Nothing is predicted until there is enough real history.' },
  INSUFFICIENT_DATA: { label: 'Collecting historical data', tone: 'bg-amber-500/20 text-amber-300', hint: 'The history is still too short or too one-sided to learn from.' },
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
  SUDDEN_CHANGE: 'Sudden change',
  UNUSUAL_ACTIVITY: 'Motion at a normally quiet time',
}

export const isActive = (s: AnomalyStatus) => s === 'OPEN' || s === 'ACKNOWLEDGED'

export const pct = (v: number, digits = 0) => `${(v * 100).toFixed(digits)}%`
export const num = (v: number | null, digits = 2) => (v === null ? 'n/a' : v.toFixed(digits))

/**
 * What training needs versus what the stored history provides: the live dataset snapshot, or the last training run's
 * counts if the server does not report a snapshot. Null when neither is known.
 */
export function progress(s: OccupancyStatus): { label: string; have: number; need: number }[] | null {
  const d = s.data.dataset ?? s.counts
  if (!d) return null
  const r = s.requirements
  return [
    { label: `Usable ${r.slotMinutes}-minute slots`, have: d.usableSlots, need: r.minUsableSlots },
    { label: 'Calendar days covered', have: d.distinctDays, need: r.minDistinctDays },
    { label: 'Slots with motion', have: d.slotsWithMotion, need: r.minSlotsPerClass },
    { label: 'Slots without motion', have: d.slotsWithoutMotion, need: r.minSlotsPerClass },
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

export const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** The pattern as a 7 x 24 grid (rows Monday..Sunday, columns 00..23). */
export function patternGrid(p: OccupancyPattern): (PatternCell | null)[][] {
  const grid: (PatternCell | null)[][] = Array.from({ length: 7 }, () => Array<PatternCell | null>(24).fill(null))
  for (const c of p.cells) {
    if (c.dayOfWeek >= 1 && c.dayOfWeek <= 7 && c.hour >= 0 && c.hour < 24) grid[c.dayOfWeek - 1][c.hour] = c
  }
  return grid
}

/**
 * Plain-language summary of the learned routine: for weekdays and weekends, the hours the model rates busiest and
 * quietest. Only hours with evidence (slots > 0) count.
 */
export function patternSummary(p: OccupancyPattern): { weekdayBusy: number[]; weekdayQuiet: number[]; weekendBusy: number[]; weekendQuiet: number[] } {
  const byHour = (days: number[]) =>
    Array.from({ length: 24 }, (_, h) => {
      const cells = p.cells.filter((c) => days.includes(c.dayOfWeek) && c.hour === h && c.slots > 0)
      return { h, p: cells.length ? cells.reduce((s, c) => s + c.modelProbability, 0) / cells.length : null }
    }).filter((x): x is { h: number; p: number } => x.p !== null)
  const pick = (rows: { h: number; p: number }[], busy: boolean) =>
    [...rows].sort((a, b) => (busy ? b.p - a.p : a.p - b.p) || a.h - b.h).slice(0, 3).map((r) => r.h).sort((a, b) => a - b)
  const wd = byHour([1, 2, 3, 4, 5])
  const we = byHour([6, 7])
  return { weekdayBusy: pick(wd, true), weekdayQuiet: pick(wd, false), weekendBusy: pick(we, true), weekendQuiet: pick(we, false) }
}

export const hourLabel = (h: number) => `${String(h).padStart(2, '0')}:00`

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
  return Array.from(new Set([DISPLAY_TZ, 'UTC', browserZone(), ...zones]))
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
    previous: 'Previous reading',
    change: 'Change',
    seconds: 'Seconds between readings',
    typicalChange: 'Typical change',
    pairs: 'Consecutive pairs in history',
    localTime: 'Local time',
    observedRate: 'Motion share at this hour',
    slotsAtThisHour: 'Past slots at this hour',
    modelProbability: 'Model probability',
    quietAtMost: 'Quiet if share at most',
  }
  return Object.entries(details)
    .filter(([k]) => k in label)
    .map(([k, v]) => [label[k], typeof v === 'number' ? String(Math.round(v * 100) / 100) : String(v)])
}
