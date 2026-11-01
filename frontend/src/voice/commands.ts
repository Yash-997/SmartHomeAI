import type { Command, CommandStatus, Device } from '../api'
import { describeCommand } from './intent'

export type CommandPhase = 'loading' | 'confirm' | 'sending' | 'waiting' | 'done' | 'cancelled' | 'refused' | 'error'

/** A device command proposed from the assistant input. It lives only in this page; nothing is sent until confirmed. */
export interface CommandItem {
  id: string
  /** Conversation it belongs to ('draft' before the first answer). */
  convKey: string
  /** Shown before the message at this index of the conversation. */
  afterIndex: number
  said: string
  spoken: boolean
  action: string
  value: string
  phase: CommandPhase
  devices: Device[]
  deviceId: string | null
  command: Command | null
  message: string
}

export const FINAL: CommandStatus[] = ['ACKNOWLEDGED', 'FAILED', 'EXPIRED', 'UNKNOWN']

/** One sentence about the outcome, suitable for reading aloud. Success is only ever claimed after an acknowledgement. */
export function outcomeText(item: CommandItem, c: Command): string {
  const name = item.devices.find((d) => d.id === item.deviceId)?.name ?? 'The device'
  const what = describeCommand(c.action, c.value).replace(/^Turn the |^Set the /, 'the ')
  switch (c.status) {
    case 'ACKNOWLEDGED':
      return `Done. ${name} confirmed the change: ${what}.`
    case 'FAILED':
      return `${name} did not apply it${c.detail ? `: ${c.detail}` : ''}.`
    case 'EXPIRED':
      return `The command expired before ${name} confirmed it, so nothing is known to have changed.`
    case 'UNKNOWN':
      return `${name} did not confirm the command, so I can't tell whether it changed.`
    default:
      return `No confirmation from ${name} yet. Check the Devices page for the final result.`
  }
}
