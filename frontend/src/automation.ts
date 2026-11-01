// Plain logic for the Automation page: labels, summaries, form drafts, and validation that mirrors the server.
// The server stays the source of truth; this only gives quick, friendly feedback before a request is sent.
import type { Device, ExecutionOutcome, Rule, RuleCondition, RuleRequest, SensorId } from './api'

export const SENSORS: { id: SensorId; label: string; unit: string; hint: string; min: number; max: number }[] = [
  { id: 'temperature', label: 'Temperature', unit: '°C', hint: '-40 to 125', min: -40, max: 125 },
  { id: 'humidity', label: 'Humidity', unit: '%', hint: '0 to 100', min: 0, max: 100 },
  { id: 'motion', label: 'Motion (PIR)', unit: '', hint: 'detected or not', min: 0, max: 1 },
  { id: 'gas', label: 'Gas (MQ-2 raw)', unit: 'raw', hint: 'whole number 0 to 4095, raw ADC, not ppm', min: 0, max: 4095 },
]

export const NUMERIC_OPERATORS = ['>', '>=', '<', '<=', '==', '!=']
export const MOTION_OPERATORS = ['==', '!=']

export const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

export const ACTION_LABEL: Record<string, string> = {
  set_pir_led: 'PIR indicator LED',
  set_gas_led: 'Gas warning LED',
}

export const OUTCOME: Record<ExecutionOutcome, { label: string; tone: string }> = {
  EXECUTED: { label: 'Sent', tone: 'bg-green-500/20 text-green-300' },
  FAILED: { label: 'Failed', tone: 'bg-red-500/20 text-red-300' },
  SKIPPED_COOLDOWN: { label: 'Cooling down', tone: 'bg-slate-600 text-slate-200' },
  SKIPPED_OFFLINE: { label: 'Device offline', tone: 'bg-amber-500/20 text-amber-300' },
  SKIPPED_NO_DATA: { label: 'No data', tone: 'bg-amber-500/20 text-amber-300' },
  SKIPPED_STALE_DATA: { label: 'Data too old', tone: 'bg-amber-500/20 text-amber-300' },
  SKIPPED_CONFLICT: { label: 'Overridden', tone: 'bg-slate-600 text-slate-200' },
  SKIPPED_ALREADY_SET: { label: 'Already set', tone: 'bg-slate-600 text-slate-200' },
  SKIPPED_RATE_LIMITED: { label: 'Rate limited', tone: 'bg-amber-500/20 text-amber-300' },
  SKIPPED_BUSY: { label: 'Busy', tone: 'bg-slate-600 text-slate-200' },
  SKIPPED_NOT_OWNED: { label: 'Device not yours', tone: 'bg-red-500/20 text-red-300' },
  SKIPPED_UNSAFE: { label: 'Refused (unsafe)', tone: 'bg-red-500/20 text-red-300' },
  SKIPPED_CONDITIONS_FALSE: { label: 'Conditions not met', tone: 'bg-slate-600 text-slate-200' },
  SKIPPED_MISSED: { label: 'Missed', tone: 'bg-amber-500/20 text-amber-300' },
}

const sensorLabel = (id: string) => SENSORS.find((s) => s.id === id)?.label ?? id

export function describeCondition(c: RuleCondition): string {
  if (c.sensor === 'motion') return `Motion ${c.operator === '==' ? 'is' : 'is not'} ${c.value ? 'detected' : 'absent'}`
  const unit = SENSORS.find((s) => s.id === c.sensor)?.unit ?? ''
  return `${sensorLabel(c.sensor)} ${c.operator} ${c.value}${unit && unit !== 'raw' ? ' ' + unit : ''}`
}

export function describeAction(action: string, value: string): string {
  return `set ${ACTION_LABEL[action] ?? action} to ${value}`
}

export function describeWhen(rule: Rule): string {
  const conds = rule.conditions.map(describeCondition).join(rule.logic === 'AND' ? ' AND ' : ' OR ')
  if (rule.trigger.type === 'SCHEDULE') {
    const days = rule.trigger.days && rule.trigger.days.length < 7 ? rule.trigger.days.join(', ') : 'every day'
    return `At ${rule.trigger.time} (${rule.trigger.timeZone}), ${days}${conds ? `, if ${conds}` : ''}`
  }
  return `When ${conds}`
}

export function deviceName(devices: Device[], id: string): string {
  return devices.find((d) => d.id === id)?.name ?? 'Unknown device'
}

export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds} s`
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`
  return `${Math.round((seconds / 3600) * 10) / 10} h`
}

// ---- form draft -----------------------------------------------------------------------------------------------------

export interface ConditionDraft {
  sensor: SensorId
  operator: string
  /** Text from the input; for motion it is 'true' or 'false'. */
  value: string
}

export interface Draft {
  name: string
  deviceId: string
  /** '' = same device. */
  targetDeviceId: string
  trigger: 'TELEMETRY' | 'SCHEDULE'
  time: string
  days: string[]
  timeZone: string
  logic: 'AND' | 'OR'
  conditions: ConditionDraft[]
  action: string
  actionValue: string
  priority: string
  cooldown: string
  enabled: boolean
}

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

export function timeZones(): string[] {
  let zones: string[] = []
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    // older browsers: fall back to the one we know
  }
  const own = browserTimeZone()
  return Array.from(new Set(['UTC', own, ...zones]))
}

export function emptyDraft(deviceId: string): Draft {
  return {
    name: '',
    deviceId,
    targetDeviceId: '',
    trigger: 'TELEMETRY',
    time: '22:00',
    days: [...DAYS],
    timeZone: browserTimeZone(),
    logic: 'AND',
    conditions: [{ sensor: 'temperature', operator: '>', value: '' }],
    action: 'set_pir_led',
    actionValue: 'on',
    priority: '10',
    cooldown: '60',
    enabled: true,
  }
}

export function draftFromRule(r: Rule): Draft {
  return {
    name: r.name,
    deviceId: r.deviceId,
    targetDeviceId: r.targetDeviceId === r.deviceId ? '' : r.targetDeviceId,
    trigger: r.trigger.type,
    time: r.trigger.time ?? '22:00',
    days: r.trigger.days ?? [...DAYS],
    timeZone: r.trigger.timeZone ?? browserTimeZone(),
    logic: r.logic,
    conditions: r.conditions.map((c) => ({ sensor: c.sensor, operator: c.operator, value: String(c.value) })),
    action: r.action.action,
    actionValue: r.action.value,
    priority: String(r.priority),
    cooldown: String(r.cooldownSeconds),
    enabled: r.enabled,
  }
}

/** Field errors keyed like the server's: name, conditions[0].value, trigger.time, ... */
export type FieldErrors = Record<string, string>

export function validateDraft(d: Draft): FieldErrors {
  const e: FieldErrors = {}
  if (!d.name.trim() || d.name.trim().length > 60) e.name = 'Give the rule a name (up to 60 characters)'
  if (!d.deviceId) e.deviceId = 'Choose a device'
  if (d.trigger === 'TELEMETRY' && d.conditions.length === 0) e.conditions = 'Add at least one condition'
  if (d.conditions.length > 10) e.conditions = 'At most 10 conditions'
  d.conditions.forEach((c, i) => {
    const s = SENSORS.find((x) => x.id === c.sensor)!
    if (c.sensor === 'motion') {
      if (c.value !== 'true' && c.value !== 'false') e[`conditions[${i}].value`] = 'Choose detected or not detected'
      return
    }
    const n = Number(c.value)
    if (c.value.trim() === '' || Number.isNaN(n)) e[`conditions[${i}].value`] = 'Enter a number'
    else if (n < s.min || n > s.max) e[`conditions[${i}].value`] = `Must be between ${s.min} and ${s.max}`
    else if (c.sensor === 'gas' && !Number.isInteger(n)) e[`conditions[${i}].value`] = 'Gas is a whole number (raw reading)'
  })
  if (d.trigger === 'SCHEDULE') {
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(d.time)) e['trigger.time'] = 'Pick a time'
    if (d.days.length === 0) e['trigger.days'] = 'Pick at least one day'
    if (!d.timeZone) e['trigger.timeZone'] = 'Choose a time zone'
  }
  const pr = Number(d.priority)
  if (d.priority.trim() === '' || !Number.isInteger(pr) || pr < 0 || pr > 100) e.priority = 'Whole number from 0 to 100'
  const cd = Number(d.cooldown)
  if (d.cooldown.trim() === '' || !Number.isInteger(cd) || cd < 5 || cd > 86400) e.cooldownSeconds = 'Whole seconds, from 5 to 86400'
  if (d.action === 'set_gas_led' && d.actionValue === 'off') e['action.value'] = 'The gas warning LED can never be turned off'
  return e
}

export function buildRequest(d: Draft): RuleRequest {
  return {
    name: d.name.trim(),
    deviceId: d.deviceId,
    targetDeviceId: d.targetDeviceId || undefined,
    enabled: d.enabled,
    trigger:
      d.trigger === 'SCHEDULE'
        ? { type: 'SCHEDULE', time: d.time, days: d.days, timeZone: d.timeZone }
        : { type: 'TELEMETRY' },
    logic: d.logic,
    conditions: d.conditions.map((c) => ({
      sensor: c.sensor,
      operator: c.operator,
      value: c.sensor === 'motion' ? c.value === 'true' : Number(c.value),
    })),
    action: { action: d.action, value: d.actionValue },
    priority: Number(d.priority),
    cooldownSeconds: Number(d.cooldown),
  }
}
