import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device } from '../api'
import AddDevice from '../components/AddDevice'
import Header from '../components/Header'
import OutputControls from '../components/OutputControls'
import TelemetryPanel, { ConnectionBadge } from '../components/TelemetryPanel'
import { liveStatus } from '../telemetry'

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
    <li className="rounded-xl bg-slate-800 p-4 shadow-lg">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
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
              <button disabled={busy} className="rounded-md bg-indigo-600 px-3 py-1 text-sm hover:bg-indigo-500">Save</button>
              <button type="button" onClick={() => { setEditing(false); setName(device.name); setError('') }}
                className="rounded-md bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600">Cancel</button>
            </form>
          ) : (
            <h3 className="truncate font-semibold">{device.name}</h3>
          )}
          <p className="mt-1 font-mono text-xs text-slate-500">{device.deviceId}</p>
          <p className="mt-1 text-xs text-slate-500">Paired {new Date(device.registeredAt).toLocaleString()}</p>
        </div>

        <ConnectionBadge connection={liveStatus(device, elapsedSeconds).connection} />
      </div>

      <TelemetryPanel device={device} elapsedSeconds={elapsedSeconds} />
      <OutputControls device={device} elapsedSeconds={elapsedSeconds} onChanged={onChanged} />

      {error && <p className="mt-3 text-sm text-red-400">{error}</p>}

      {!confirming ? (
        <div className="mt-3 flex gap-2">
          <button onClick={() => setEditing(true)} disabled={editing}
            className="rounded-md bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600 disabled:opacity-50">Rename</button>
          <button onClick={() => setConfirming(true)}
            className="rounded-md bg-red-900/60 px-3 py-1 text-sm text-red-200 hover:bg-red-900">Disown</button>
        </div>
      ) : (
        <div className="mt-3 rounded-md bg-red-950/50 p-3 text-sm">
          <p>
            Disown <strong>{device.name}</strong>? Its credentials will be revoked and it will stop working until it is
            paired again with a new code.
          </p>
          <div className="mt-3 flex gap-2">
            <button disabled={busy} onClick={() => run(() => api.disownDevice(device.id), () => setConfirming(false))}
              className="rounded-md bg-red-600 px-3 py-1 hover:bg-red-500 disabled:opacity-60">Yes, disown</button>
            <button onClick={() => setConfirming(false)} className="rounded-md bg-slate-700 px-3 py-1 hover:bg-slate-600">Keep</button>
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
    <main className="min-h-screen bg-slate-900 p-6 text-slate-100">
      <div className="mx-auto max-w-2xl space-y-6">
        <Header />

        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Your devices</h2>
          <button onClick={() => setAdding(true)} className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium hover:bg-indigo-500">
            Add device
          </button>
        </div>

        {load.kind === 'loading' && <p className="text-slate-400">Loading...</p>}
        {load.kind === 'error' && <p className="rounded bg-red-500/10 p-3 text-sm text-red-400">{load.message}</p>}
        {load.kind === 'ready' && devices.length === 0 && (
          <p className="rounded-xl bg-slate-800 p-6 text-center text-sm text-slate-400">
            No devices yet. Click "Add device" to pair your ESP32.
          </p>
        )}
        <ul className="space-y-3">
          {devices.map((d) => (
            <DeviceCard key={d.id} device={d} elapsedSeconds={elapsedSeconds} onChanged={refresh} />
          ))}
        </ul>
      </div>

      {adding && (
        <AddDevice knownIds={devices.map((d) => d.id)} onClose={() => setAdding(false)} onPaired={refresh} />
      )}
    </main>
  )
}
