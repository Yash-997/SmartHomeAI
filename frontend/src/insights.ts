// Labels, tones and small calculations for the ML Insights page. No network, no React.
import type { AnomalyKind, AnomalySeverity, AnomalyStatus, MlMetrics, MlStatus, OccupancyStatus } from './api'

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

/** Makes a status response safe to render: anything missing stays unknown (no model), never an invented value. */
export function normalizeStatus(raw: MlStatus): MlStatus {
  const o = (raw.occupancy ?? {}) as Partial<OccupancyStatus>
  return {
    occupancy: {
      state: o.state ?? 'UNAVAILABLE',
      dataLabel: o.dataLabel ?? '',
      version: o.version ?? 0,
      trainedAt: o.trainedAt ?? null,
      trainingRows: o.trainingRows ?? 0,
      trainingDays: o.trainingDays ?? 0,
      dataset: o.dataset ?? null,
      algorithms: o.algorithms ?? {},
      message: o.message ?? 'The server did not report a model.',
    },
    anomalies: raw.anomalies ?? { active: 0, critical: 0, warning: 0, info: 0, lastScanAt: null },
    notice: raw.notice ?? '',
  }
}

export const STATE: Record<OccupancyStatus['state'], { label: string; tone: string }> = {
  READY: { label: 'Ready', tone: 'bg-green-500/20 text-green-300' },
  PREPARING: { label: 'Preparing', tone: 'bg-slate-600 text-slate-100' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'bg-red-500/20 text-red-300' },
}

/** One-line verdict on whether a per-device synthetic benchmark model beat simply guessing the average. */
export function verdict(m: MlMetrics): { better: boolean; text: string } {
  const better = m.logLoss < m.baselineLogLoss
  const numbers = `(log loss ${m.logLoss.toFixed(3)} vs ${m.baselineLogLoss.toFixed(3)})`
  return { better, text: `${better ? 'Beats' : 'Not better than'} the "always guess the average" baseline ${numbers}.` }
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
  MULTIVARIATE_OUTLIER: 'Unusual sensor pattern (Isolation Forest)',
}

export const isActive = (s: AnomalyStatus) => s === 'OPEN' || s === 'ACKNOWLEDGED'

export const pct = (v: number, digits = 0) => `${(v * 100).toFixed(digits)}%`
export const num = (v: number | null, digits = 2) => (v === null ? 'n/a' : v.toFixed(digits))

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
