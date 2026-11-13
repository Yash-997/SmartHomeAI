import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device } from '../api'
import AddDevice from '../components/AddDevice'
import OutputControls from '../components/OutputControls'
import Shell, { PageHeader } from '../components/Shell'
import TelemetryPanel, { ConnectionBadge } from '../components/TelemetryPanel'
import { liveStatus } from '../telemetry'
import { DevicesIcon, PencilIcon, PlusIcon } from '../ui/icons'

// fetchedAt (browser ms) lets cards keep their "x seconds ago" ticking between polls.
type Load = { kind: 'loading' } | { kind: 'ready'; devices: Device[]; fetchedAt: number } | { kind: 'error'; message: string }

const POLL_MS = 5000
// While a command is still on its way, check back every second so progress shows up promptly.
const FAST_POLL_MS = 1000

function DeviceCard({ device, elapsedSeconds, onChanged }: { device: Device; elapsedSeconds: number; onChanged: () => void }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(device.name)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const run = async (fn: () => Promise<unknown>, done: () => void) => {
    setBusy(true)
    setError('')
    try {
      await fn()
      done()
      onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors.name ?? err.message) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="card p-5">
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-slate-700 text-indigo-400"><DevicesIcon /></span>
        <div className="min-w-0 flex-1">
          {editing ? (
            <form
              className="flex gap-2"
              onSubmit={(e) => {
                e.preventDefault()
                if (!name.trim()) return setError('Name cannot be empty')
                run(() => api.renameDevice(device.id, name.trim()), () => setEditing(false))
              }}
            >
              <input aria-label="Device name" value={name} maxLength={60} onChange={(e) => setName(e.target.value)}
                className="rounded-md border border-slate-600 bg-slate-900 px-2 py-1 text-sm" autoFocus />
              <button disabled={busy} className="btn-primary rounded-lg px-3 py-1 text-sm">Save</button>
              <button type="button" onClick={() => { setEditing(false); setName(device.name); setError('') }}
                className="btn-secondary rounded-lg px-3 py-1 text-sm">Cancel</button>
            </form>
          ) : (
            <h3 className="truncate text-lg font-semibold leading-tight">{device.name}</h3>
          )}
          <p className="mt-0.5 truncate text-xs text-slate-500">
            <span className="font-mono" title="Device ID">{device.deviceId}</span> · paired {new Date(device.registeredAt).toLocaleDateString()}
          </p>
        </div>

        <ConnectionBadge connection={liveStatus(device, elapsedSeconds).connection} />
      </div>

      <TelemetryPanel device={device} elapsedSeconds={elapsedSeconds} />
      <OutputControls device={device} elapsedSeconds={elapsedSeconds} onChanged={onChanged} />

      {error && <p className="mt-3 text-sm text-red-400">{error}</p>}

      {!confirming ? (
        <div className="mt-5 flex gap-2 border-t border-slate-700/60 pt-4">
          <button onClick={() => setEditing(true)} disabled={editing}
            className="btn-secondary inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"><PencilIcon width={15} height={15} />Rename</button>
          <button onClick={() => setConfirming(true)}
            className="btn-danger ml-auto rounded-lg px-3 py-1.5 text-sm">Disown</button>
        </div>
      ) : (
        <div role="alertdialog" aria-label={`Disown ${device.name}`} className="mt-3 rounded-lg border border-red-400/30 bg-red-950/50 p-3 text-sm">
          <p>
            Disown <strong>{device.name}</strong>? Its credentials will be revoked and it will stop working until it is
            paired again with a new code.
          </p>
          <div className="mt-3 flex gap-2">
            <button disabled={busy} onClick={() => run(() => api.disownDevice(device.id), () => setConfirming(false))}
              className="rounded-lg bg-red-500 px-3 py-1.5 font-medium text-slate-950 hover:bg-red-400 disabled:opacity-60">Yes, disown</button>
            <button onClick={() => setConfirming(false)} className="btn-secondary rounded-lg px-3 py-1.5">Keep</button>
          </div>
        </div>
      )}
    </li>
  )
}

export default function Devices() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [adding, setAdding] = useState(false)

  const refresh = useCallback(() => {
    api
      .listDevices()
      .then((devices) => setLoad({ kind: 'ready', devices, fetchedAt: Date.now() }))
      // A 401 is handled globally (session ends). A failed refresh keeps showing the last data it had.
      .catch((e) =>
        setLoad((prev) =>
          prev.kind === 'ready'
            ? prev
            : { kind: 'error', message: e instanceof ApiError ? e.message : 'Failed to load devices' },
        ),
      )
  }, [])

  const commandInFlight =
    load.kind === 'ready' &&
    load.devices.some((d) => d.recentCommands.some((c) => c.status === 'PENDING' || c.status === 'SENT'))

  useEffect(() => {
    refresh()
    const poll = setInterval(refresh, commandInFlight ? FAST_POLL_MS : POLL_MS)
    return () => clearInterval(poll)
  }, [refresh, commandInFlight])

  // Re-render every second so ages ("12s ago") and stale/offline decisions stay current between polls.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(tick)
  }, [])
  const elapsedSeconds = load.kind === 'ready' ? Math.max(0, (now - load.fetchedAt) / 1000) : 0

  const devices = load.kind === 'ready' ? load.devices : []

  return (
    <Shell>
      <PageHeader
        title="Devices"
        subtitle="Pair, rename and control your devices."
        actions={<button onClick={() => setAdding(true)} className="btn-primary inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm"><PlusIcon width={16} height={16} />Add device</button>}
      />

      {load.kind === 'loading' && <p role="status" className="text-slate-400">Loading...</p>}
      {load.kind === 'error' && <p role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300">{load.message} <button type="button" onClick={refresh} className="underline">Try again</button></p>}
      {load.kind === 'ready' && devices.length === 0 && (
        <div className="card p-8 text-center">
          <h2 className="text-lg font-semibold">No devices yet</h2>
          <p className="mx-auto mt-1 max-w-sm text-sm text-slate-400">Pair your ESP32 to see its readings and control its outputs.</p>
          <button type="button" onClick={() => setAdding(true)} className="btn-primary mt-5 rounded-lg px-5 py-2.5 text-sm">Add device</button>
        </div>
      )}
      <ul className={`grid gap-4 ${devices.length > 1 ? 'xl:grid-cols-2' : ''}`}>
        {devices.map((d) => (
          <DeviceCard key={d.id} device={d} elapsedSeconds={elapsedSeconds} onChanged={refresh} />
        ))}
      </ul>

      {adding && (
        <AddDevice knownIds={devices.map((d) => d.id)} onClose={() => setAdding(false)} onPaired={refresh} />
      )}
    </Shell>
  )
}
