import { useState } from 'react'
import { api, ApiError, type Command, type CommandStatus, type Device, type OutputSpec } from '../api'
import { formatAge, liveStatus } from '../telemetry'
import { ChevronDownIcon } from '../ui/icons'

const STATUS: Record<CommandStatus, { label: string; tone: string }> = {
  PENDING: { label: 'Sending...', tone: 'bg-slate-600 text-slate-100' },
  SENT: { label: 'Sent, waiting for the device...', tone: 'bg-sky-500/20 text-sky-300' },
  ACKNOWLEDGED: { label: 'Confirmed by device', tone: 'bg-green-500/20 text-green-300' },
  FAILED: { label: 'Failed', tone: 'bg-red-500/20 text-red-300' },
  EXPIRED: { label: 'Expired', tone: 'bg-amber-500/20 text-amber-300' },
  UNKNOWN: { label: 'Not confirmed (outcome unknown)', tone: 'bg-amber-500/20 text-amber-300' },
}

const VALUE_LABEL: Record<string, string> = { auto: 'Auto', on: 'On', off: 'Off' }

function describe(c: Command, outputs: OutputSpec[]): string {
  const out = outputs.find((o) => o.action === c.action)
  return `${out?.label ?? c.action}: ${VALUE_LABEL[c.value] ?? c.value}`
}

/** The mode/on pair this output reports, from the device's own state report. */
function reported(device: Device, action: string) {
  const r = device.reportedState
  if (!r) return null
  if (action === 'set_pir_led') return r.pirLed
  if (action === 'set_gas_led') return r.gasLed
  return null
}

export default function OutputControls({
  device,
  elapsedSeconds,
  onChanged,
}: {
  device: Device
  elapsedSeconds: number
  onChanged: () => void
}) {
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState('')

  const { connection } = liveStatus(device, elapsedSeconds)
  const online = connection === 'ONLINE'
  const state = device.reportedState
  const stateAge = state ? state.ageSeconds + elapsedSeconds : null
  const stateStale = state !== null && stateAge !== null && stateAge > device.staleAfterSeconds

  const send = async (o: OutputSpec, value: string) => {
    setBusy(`${o.action}:${value}`)
    setError('')
    try {
      await api.sendCommand(device.id, o.action, value)
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors.value ?? err.fieldErrors.action ?? err.message) : 'Something went wrong')
    } finally {
      setBusy(null)
      onChanged()
    }
  }

  if (device.outputs.length === 0) return null

  return (
    <div className="mt-5">
      <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">Outputs</h4>

      {state && state.gasWarning && !stateStale && (
        <p className="alert-critical mt-2 rounded bg-red-500/20 p-2 text-sm font-medium text-red-300" role="alert">
          Gas warning active on the device. Its warning LED stays on until the reading clears.
        </p>
      )}

      {!online && (
        <p className="mt-2 text-xs text-slate-400">
          Controls need the device to be online ({connection === 'NEVER_CONNECTED' ? 'it has not connected yet' : 'it is offline'}).
        </p>
      )}

      <ul className="mt-3 space-y-3">
        {device.outputs.map((o) => {
          const rep = reported(device, o.action)
          return (
            <li key={o.action} className="rounded-xl bg-slate-900/60 p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-medium">{o.label}</span>
                <span className={`text-xs ${stateStale ? 'text-amber-300' : 'text-slate-400'}`}>
                  {rep
                    ? `Device reports: ${rep.on ? 'ON' : 'off'} (${rep.mode})${stateAge !== null ? `, ${formatAge(stateAge)} ago` : ''}${stateStale ? ' · STALE' : ''}`
                    : 'No state reported by the device yet'}
                </span>
              </div>
              <div className="mt-2 inline-flex gap-1 rounded-xl bg-slate-950/60 p-1" role="group" aria-label={o.label}>
                {o.values.map((v) => {
                  const active = rep !== null && !stateStale && rep.mode === v
                  const key = `${o.action}:${v}`
                  return (
                    <button
                      key={v}
                      type="button"
                      disabled={!online || busy !== null}
                      onClick={() => send(o, v)}
                      aria-pressed={active}
                      className={`min-w-14 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
                        active ? 'bg-indigo-400 text-slate-950 shadow-sm hover:bg-indigo-300' : 'text-slate-300 hover:bg-slate-700'
                      }`}
                    >
                      {busy === key ? '...' : (VALUE_LABEL[v] ?? v)}
                    </button>
                  )
                })}
              </div>
              {o.action === 'set_gas_led' && (
                <p className="mt-1 text-[11px] text-slate-500">
                  Lights automatically on a gas warning. It can be forced on, but never turned off remotely.
                </p>
              )}
            </li>
          )
        })}
      </ul>

      {error && <p className="mt-2 text-sm text-red-400">{error}</p>}

      {device.recentCommands.length > 0 && (
        <details className="group mt-3 rounded-xl bg-slate-900/40">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-2 rounded-xl px-3 py-2 text-xs font-medium text-slate-400 marker:hidden hover:text-slate-100 [&::-webkit-details-marker]:hidden">
            <span>Recent commands ({device.recentCommands.length})</span>
            <ChevronDownIcon width={16} height={16} aria-hidden="true" className="transition-transform group-open:rotate-180" />
          </summary>
          <ul className="space-y-2 px-3 pb-3">
            {device.recentCommands.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-2 text-xs">
                <span className="text-slate-300">{describe(c, device.outputs)}</span>
                {c.origin && <span className="rounded bg-slate-700 px-1.5 py-0.5 text-slate-300">{c.origin.startsWith('automation:') ? `Automation: ${c.origin.slice(11)}` : c.origin}</span>}
                <span className={`rounded px-2 py-0.5 ${STATUS[c.status].tone}`}>{STATUS[c.status].label}</span>
                {c.detail && c.status !== 'ACKNOWLEDGED' && <span className="text-slate-500">{c.detail}</span>}
                <span className="text-slate-600">{new Date(c.createdAt).toLocaleTimeString()}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
