import type { Device } from './api'

export type Tone = 'ok' | 'warn' | 'alarm' | 'muted'

export interface GasView {
  /** The latest raw MQ-2 value, or null when the latest report has none. Never filled in from older data. */
  raw: number | null
  status: string
  tone: Tone
  /** The device's own (local) gas warning, as last reported; null when there is no fresh report. */
  warning: boolean | null
}

export interface MotionView {
  motion: boolean | null
  label: string
}

/** What to show for a device's MQ-2: the real reading and an honest status, never an invented number. */
export function gasView(d: Device, readingStale: boolean): GasView {
  const t = d.latestTelemetry
  const rs = d.reportedState
  const warning = rs && !rs.stale ? rs.gasWarning : null
  if (!t) return { raw: null, status: 'No readings received yet', tone: 'muted', warning }
  const raw = t.gasRaw
  if (raw === null) {
    return { raw, status: 'No gas value in the latest report (sensor warming up or not readable)', tone: 'muted', warning }
  }
  if (readingStale) return { raw, status: 'Stale: this is the last known value', tone: 'muted', warning }
  if (warning) return { raw, status: 'Gas warning active on the device', tone: 'alarm', warning }
  if (raw === 0) return { raw, status: 'Reads 0: the sensor output is not reaching the ESP32', tone: 'warn', warning }
  return { raw, status: 'Reading normally', tone: 'ok', warning }
}

export function motionView(d: Device, readingStale: boolean): MotionView {
  const m = d.latestTelemetry?.motion ?? null
  if (m === null) return { motion: null, label: 'No reading' }
  if (readingStale) return { motion: m, label: m ? 'Motion (stale)' : 'No motion (stale)' }
  return { motion: m, label: m ? 'Motion detected' : 'No motion' }
}
