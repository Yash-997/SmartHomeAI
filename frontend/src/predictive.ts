import { authedJson, type Command, type OccupancyStatus } from './api'

export type Override = 'NONE' | 'ON' | 'OFF'
export type ScenarioId =
  | 'NORMAL_WEEKDAY' | 'PREDICTIVE_ARRIVAL' | 'NIGHT_MODE' | 'UNUSUAL_WAKE' | 'DELAYED_RETURN' | 'MANUAL_OVERRIDE' | 'MISSED_ROUTINE'

export interface Settings {
  nightEnabled: boolean
  /** "HH:mm" or "HH:mm:ss" */
  nightStart: string
  nightEnd: string
  predictiveEnabled: boolean
  onThreshold: number
  offThreshold: number
  arrivalThreshold: number
  confidenceThreshold: number
  preArrivalMinutes: number
  cooldownSeconds: number
  motionHoldMinutes: number
  preArrivalTimeoutMinutes: number
  predictionMaxAgeSeconds: number
}

export interface Presence { probability: number; state: 'LIKELY_OCCUPIED' | 'LIKELY_EMPTY' | 'UNCERTAIN'; confidence: number }
export interface Arrival {
  horizons: { minutes: number; probability: number; confidence: number }[]
  windowFrom: number | null
  windowTo: number | null
  median: number | null
  supportDays: number
  comparableDays: number
}
export interface Decision { cause?: string; type: string; command: 'on' | 'off' | 'auto' | null; reason: string }

interface Shared {
  dataLabel: string
  now: string
  presence: Presence | null
  arrival: Arrival | null
  arrivalApplicable: boolean
  nightActive: boolean
  settings: Settings
  override: Override
  light: { mode: string; on: boolean }
  decision: Decision
  lastTrainedAt: string | null
  modelVersion: number
  preArrival: { since: string | null; expired: boolean }
}

export interface Routine { wake: number | null; wakeSpread: number | null; leave: number | null; returnFrom: number | null; returnTo: number | null; days: number }
export interface PredictionNow { at: string; timeZone: string; presence: Presence; modelVersion: number; dataLabel: string }

export interface Simulation extends Shared {
  mode: 'SIMULATION'
  scenario: ScenarioId
  minute: number
  today: { wake: number; leave: number; back: number }
  sustainDays: number
  learned: Routine
  learnedWeekend: Routine
  deviation: { kind: string; explanation: string; differenceMinutes: number | null; consecutiveDays: number }
  acknowledgement: string
  timeline: { minute: number; motion: number; predicted: number; actual: boolean }[]
  activity: { weekdayPresence: number[]; weekendPresence: number[]; weekdayMotion: number[]; weekendMotion: number[] }
  log: { minute: number; type: string; command: string | null; reason: string }[]
  dataset: NonNullable<OccupancyStatus['dataset']>
}

export interface Live extends Shared {
  mode: 'LIVE_ESP32'
  armed: boolean
  timeZone: string
  connection: string
  sensorFresh: boolean
  sensorAgeSeconds: number | null
  motionReadingsToday: number
  minutesSinceMotion: number | null
  deviceStateReported: boolean
  commands: Command[]
  sendError: string | null
  events: { at: string; type: string; command: string | null; cause: string; reason: string }[]
}

export interface Score {
  n: number
  truePositive: number
  falsePositive: number
  falseNegative: number
  trueNegative: number
  accuracy: number
  precision: number | null
  recall: number | null
  f1: number | null
  auc: number | null
  logLoss: number
  brier: number
}
export interface Evaluation {
  data: string
  method: string
  baselines: string
  results: {
    target: string
    chosen: string
    validationLogLoss: Record<string, number>
    model: Score
    baseline: Score
    calibration: { from: number; to: number; n: number; meanPredicted: number; observedRate: number }[]
  }[]
  arrivalMedianErrorMinutes: number | null
  arrivalWindowCoverage: number | null
  arrivalDaysScored: number
}

const SIM = '/api/predictive/simulation'

export const predictiveApi = {
  simulation: () => authedJson<Simulation>(SIM),
  act: (action: string, fields: Record<string, unknown> = {}) => authedJson<Simulation>(SIM, { action, ...fields }),
  evaluation: () => authedJson<Evaluation>('/api/predictive/evaluation'),
  now: () => authedJson<PredictionNow>('/api/predictive/now'),
  train: () => authedJson<OccupancyStatus>('/api/predictive/model/train', {}),
  regenerate: (seed: number) => authedJson<OccupancyStatus>('/api/predictive/dataset/regenerate', { seed }),
  sendToDevice: (deviceId: string, value: string) => authedJson<Command>(`${SIM}/send-to-device`, { deviceId, value }),
  command: (deviceId: string, commandId: string) => authedJson<Command>(`/api/devices/${deviceId}/commands/${commandId}`),
  live: (deviceId: string) => authedJson<Live>(`/api/predictive/live/${deviceId}`),
  configureLive: (deviceId: string, body: { armed?: boolean; override?: Override; settings?: Settings }) =>
    authedJson<Live>(`/api/predictive/live/${deviceId}`, body),
}

export const hhmm = (minute: number | null | undefined) =>
  minute == null ? '—' : `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`

export const toMinute = (text: string) => {
  const [h, m] = text.split(':').map(Number)
  return h * 60 + m
}

export const pct = (v: number | null | undefined) => (v == null ? '—' : `${Math.round(v * 100)}%`)
