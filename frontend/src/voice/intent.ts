// Recognises a spoken or typed LED command. Deliberately deterministic and narrow: only an imperative sentence that
// names one output and one mode counts, so a question ("is the PIR LED on?") is never mistaken for a command. A
// recognised command is only a proposal; nothing is sent until the user confirms it (see Assistant.tsx).
import type { Device } from '../api'

export type CommandValue = 'on' | 'off' | 'auto'

export type DeviceIntent =
  | { kind: 'command'; action: 'set_pir_led' | 'set_gas_led'; value: CommandValue }
  | { kind: 'refused'; action: 'set_gas_led'; reason: string }

export const OUTPUT_NAME: Record<string, string> = {
  set_pir_led: 'PIR indicator LED',
  set_gas_led: 'gas warning LED',
}

const LEAD_IN = /^(?:(?:hey|hi|ok|okay|please|kindly|assistant|now|so|and|can you|could you|would you|will you|i want you to|i'd like you to)\s+)+/
const QUESTION_START = /^(?:what|whats|what's|is|are|was|were|how|why|when|where|which|who|does|do|did|has|have|tell|show|explain|describe|give|list)\b/
const VERB = /^(?:turn|switch|set|put|make|enable|disable|activate|deactivate|force|light up|light)\b/
const PIR = /\b(?:pir|motion)\b/
const GAS = /\b(?:gas|smoke|mq ?2)\b/
const OUTPUT_WORD = /\b(?:led|leds|light|lights|lamp|indicator|indicators)\b/

export function normalise(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9' ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/** null = not a device command (send it to the assistant as a question). */
export function parseDeviceCommand(text: string): DeviceIntent | null {
  const t = normalise(text).replace(LEAD_IN, '').replace(/\s+please$/, '')
  if (!t || QUESTION_START.test(t) || !VERB.test(t)) return null

  const pir = PIR.test(t)
  const gas = GAS.test(t)
  if (pir === gas || !OUTPUT_WORD.test(t)) return null // no output, or two of them: too ambiguous to act on

  let value: CommandValue | null = null
  if (/\b(?:auto|automatic|automatically|normal|default)\b/.test(t)) value = 'auto'
  else if (/\boff\b/.test(t) || /^(?:disable|deactivate)\b/.test(t)) value = 'off'
  else if (/\bon\b/.test(t) || /^(?:enable|activate|force|light)\b/.test(t)) value = 'on'
  if (!value) return null

  if (gas && value === 'off') {
    return {
      kind: 'refused',
      action: 'set_gas_led',
      reason: 'The gas warning LED cannot be turned off remotely: it always lights when a gas warning is active. You can set it to automatic or force it on.',
    }
  }
  return { kind: 'command', action: pir ? 'set_pir_led' : 'set_gas_led', value }
}

/** Devices that advertise this action and value. */
export function devicesFor(devices: Device[], action: string, value: string): Device[] {
  return devices.filter((d) => d.status === 'ACTIVE' && d.outputs.some((o) => o.action === action && o.values.includes(value)))
}

/** The one device whose name the user said, if exactly one matches. */
export function deviceNamedIn(devices: Device[], text: string): Device | null {
  const t = normalise(text)
  const hits = devices.filter((d) => {
    const n = normalise(d.name)
    return n.length > 0 && t.includes(n)
  })
  return hits.length === 1 ? hits[0] : null
}

export function describeCommand(action: string, value: string): string {
  const name = OUTPUT_NAME[action] ?? action
  if (value === 'auto') return `Set the ${name} to automatic`
  return `Turn the ${name} ${value}`
}

const YES = /^(?:yes|yeah|yep|yup|confirm|confirmed|do it|go ahead|sure|ok|okay|please do)\b/
const NO = /^(?:no|nope|cancel|stop|never ?mind|don't|do not|abort)\b/

/** 'yes' | 'no' for a short confirmation reply, null for anything else. */
export function confirmationReply(text: string): 'yes' | 'no' | null {
  const t = normalise(text)
  if (t.split(' ').length > 4) return null
  if (NO.test(t)) return 'no'
  if (YES.test(t)) return 'yes'
  return null
}
