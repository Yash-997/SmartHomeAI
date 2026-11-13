import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Connection, type Device, type OutputSpec } from '../api'
import { formatAge, liveStatus } from '../telemetry'
import { ChevronRightIcon, PlusIcon } from '../ui/icons'
import { notify } from '../ui/toastStore'

const VALUE_LABEL: Record<string, string> = { auto: 'Auto', on: 'On', off: 'Off' }
const DOT: Record<Connection, string> = { ONLINE: 'bg-green-400', OFFLINE: 'bg-red-400', NEVER_CONNECTED: 'bg-slate-500' }
const WORD: Record<Connection, string> = { ONLINE: 'Online', OFFLINE: 'Offline', NEVER_CONNECTED: 'Not connected yet' }

/** The mode this output reports, from the device's own state report. */
function reportedMode(device: Device, action: string): string | null {
  const r = device.reportedState
  if (!r || r.ageSeconds > device.staleAfterSeconds) return null
  if (action === 'set_pir_led') return r.pirLed?.mode ?? null
  if (action === 'set_gas_led') return r.gasLed?.mode ?? null
  return null
}

function Segmented({ device, output, disabled, onSend }: { device: Device; output: OutputSpec; disabled: boolean; onSend: (o: OutputSpec, v: string) => void }) {
  const active = reportedMode(device, output.action)
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-sm text-slate-300">{output.label}</span>
      <div role="group" aria-label={`${device.name} ${output.label}`} className="inline-flex rounded-lg border border-slate-700 bg-slate-900 p-0.5">
        {output.values.map((v) => (
          <button key={v} type="button" disabled={disabled} aria-pressed={active === v} onClick={() => onSend(output, v)}
            className={`rounded-md px-3 py-1 text-xs font-medium disabled:cursor-not-allowed disabled:opacity-40 ${
              active === v ? 'bg-indigo-400 text-slate-950' : 'text-slate-300 hover:bg-slate-700'
            }`}>
            {VALUE_LABEL[v] ?? v}
          </button>
        ))}
      </div>
    </div>
  )
}

function Row({ device, elapsed, onChanged }: { device: Device; elapsed: number; onChanged: () => void }) {
  const { connection, lastSeenAge } = liveStatus(device, elapsed)
  const [busy, setBusy] = useState(false)
  const online = connection === 'ONLINE'

  const send = async (o: OutputSpec, value: string) => {
    setBusy(true)
    try {
      await api.sendCommand(device.id, o.action, value)
    } catch (err) {
      notify(err instanceof ApiError ? (err.fieldErrors.value ?? err.fieldErrors.action ?? err.message) : 'Could not send the command', 'error')
    } finally {
      setBusy(false)
      onChanged()
    }
  }

  return (
    <li className="py-4 first:pt-0 last:pb-0">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{device.name}</p>
          <p className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
            <span aria-hidden="true" className={`h-2 w-2 rounded-full ${DOT[connection]}`} />
            {WORD[connection]}
            {lastSeenAge !== null && <span className="text-slate-500">· seen {formatAge(lastSeenAge)} ago</span>}
          </p>
        </div>
        <Link to="/devices" aria-label={`${device.name} details`} className="btn-secondary inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs">
          Details <ChevronRightIcon width={14} height={14} />
        </Link>
      </div>
      {device.outputs.length > 0 && (
        <div className="mt-3 space-y-2 rounded-lg bg-slate-900 p-3">
          {device.outputs.map((o) => (
            <Segmented key={o.action} device={device} output={o} disabled={!online || busy} onSend={send} />
          ))}
          {!online && <p className="text-xs text-slate-500">Controls need the device to be online.</p>}
        </div>
      )}
    </li>
  )
}

/** Connected devices with their status and a quick control for each output they support. */
export default function DeviceList({ devices, elapsed, onChanged }: { devices: Device[]; elapsed: number; onChanged: () => void }) {
  const online = devices.filter((d) => liveStatus(d, elapsed).connection === 'ONLINE').length
  return (
    <section className="card p-5" aria-labelledby="devices-heading">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 id="devices-heading" className="font-semibold">Devices</h2>
          <p className="mt-0.5 text-xs text-slate-400">{devices.length === 0 ? 'None paired yet' : `${online} of ${devices.length} online`}</p>
        </div>
        <Link to="/devices" className="btn-secondary inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"><PlusIcon width={16} height={16} />Manage</Link>
      </div>
      {devices.length === 0 ? (
        <div className="mt-4 rounded-lg bg-slate-900 p-5 text-center">
          <p className="text-sm text-slate-300">No devices yet.</p>
          <Link to="/devices" className="btn-primary mt-3 inline-block rounded-lg px-4 py-2 text-sm">Add your first device</Link>
        </div>
      ) : (
        <ul className="mt-4 divide-y divide-slate-700">
          {devices.map((d) => <Row key={d.id} device={d} elapsed={elapsed} onChanged={onChanged} />)}
        </ul>
      )}
    </section>
  )
}
