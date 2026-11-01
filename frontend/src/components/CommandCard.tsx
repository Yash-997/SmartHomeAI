import type { CommandStatus } from '../api'
import type { CommandItem } from '../voice/commands'
import { describeCommand } from '../voice/intent'

const STATUS: Record<CommandStatus, { label: string; tone: string }> = {
  PENDING: { label: 'Sending...', tone: 'bg-slate-600 text-slate-100' },
  SENT: { label: 'Sent, waiting for the device to confirm...', tone: 'bg-sky-500/20 text-sky-300' },
  ACKNOWLEDGED: { label: 'Confirmed by device', tone: 'bg-green-500/20 text-green-300' },
  FAILED: { label: 'Failed', tone: 'bg-red-500/20 text-red-300' },
  EXPIRED: { label: 'Expired', tone: 'bg-amber-500/20 text-amber-300' },
  UNKNOWN: { label: 'Not confirmed (outcome unknown)', tone: 'bg-amber-500/20 text-amber-300' },
}

interface Props {
  item: CommandItem
  voiceMode: boolean
  onDevice: (deviceId: string) => void
  onConfirm: () => void
  onCancel: () => void
}

export default function CommandCard({ item, voiceMode, onDevice, onConfirm, onCancel }: Props) {
  const title = describeCommand(item.action, item.value)
  const device = item.devices.find((d) => d.id === item.deviceId) ?? null
  return (
    <div className="flex justify-start">
      <div className="w-full max-w-[92%] rounded-2xl rounded-bl-sm border border-indigo-500/40 bg-slate-800 px-4 py-3 text-sm" aria-label="Device command">
        <p className="text-xs text-slate-400">{item.spoken ? 'You said' : 'You asked'}: “{item.said}”</p>
        <p className="mt-1 font-medium text-indigo-200">{title}</p>

        {item.phase === 'loading' && <p className="mt-2 text-slate-400">Looking up your devices...</p>}

        {item.phase === 'confirm' && (
          <>
            {item.devices.length > 1 && (
              <label className="mt-2 block text-xs text-slate-400">
                Device
                <select value={item.deviceId ?? ''} onChange={(e) => onDevice(e.target.value)}
                  className="mt-1 block w-full rounded-md border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm text-slate-100">
                  <option value="" disabled>Choose a device</option>
                  {item.devices.map((d) => <option key={d.id} value={d.id}>{d.name}{d.connection !== 'ONLINE' ? ' (offline)' : ''}</option>)}
                </select>
              </label>
            )}
            {item.devices.length === 1 && device && (
              <p className="mt-1 text-slate-300">Device: {device.name}{device.connection !== 'ONLINE' ? ' (looks offline)' : ''}</p>
            )}
            <p className="mt-2 text-xs text-slate-400">
              Nothing has been sent. {voiceMode ? 'Say “yes” or “no” (tap the microphone first), or use the buttons.' : 'Confirm to send it.'}
            </p>
            <div className="mt-2 flex gap-2">
              <button type="button" onClick={onConfirm} disabled={!item.deviceId}
                className="rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-medium hover:bg-indigo-500 disabled:opacity-50">
                Confirm and send
              </button>
              <button type="button" onClick={onCancel} className="rounded-md bg-slate-700 px-3 py-1.5 text-xs hover:bg-slate-600">Cancel</button>
            </div>
          </>
        )}

        {item.command && (
          <p className="mt-2">
            <span className={`rounded px-2 py-0.5 text-xs ${STATUS[item.command.status].tone}`} data-testid="command-status">
              {STATUS[item.command.status].label}
            </span>
          </p>
        )}
        {item.phase === 'sending' && !item.command && <p className="mt-2 text-slate-400" role="status">Sending...</p>}
        {item.phase === 'cancelled' && <p className="mt-2 text-slate-400">Cancelled: nothing was sent.</p>}
        {item.message && (
          <p className={`mt-2 ${item.phase === 'error' || item.phase === 'refused' ? 'text-red-300' : 'text-slate-200'}`} role={item.phase === 'error' ? 'alert' : undefined}>
            {item.message}
          </p>
        )}
      </div>
    </div>
  )
}
