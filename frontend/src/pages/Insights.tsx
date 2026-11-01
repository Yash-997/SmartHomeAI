import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device, type MlStatus } from '../api'
import AnomalyPanel from '../components/AnomalyPanel'
import Header from '../components/Header'
import OccupancyPanel from '../components/OccupancyPanel'

type DevicesLoad = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; devices: Device[] }
type StatusResult = { forDevice: string; error?: string; status?: MlStatus }

export default function Insights() {
  const [devices, setDevices] = useState<DevicesLoad>({ kind: 'loading' })
  const [deviceId, setDeviceId] = useState('')
  const [loaded, setLoaded] = useState<StatusResult | null>(null)
  // Loading is derived: a result only counts if it belongs to the device that is selected right now.
  const current = loaded && loaded.forDevice === deviceId ? loaded : null

  useEffect(() => {
    api
      .listDevices()
      .then((list) => {
        setDevices({ kind: 'ready', devices: list })
        setDeviceId((cur) => cur || list[0]?.id || '')
      })
      .catch((e) => setDevices({ kind: 'error', message: e instanceof ApiError ? e.message : 'Failed to load devices' }))
  }, [])

  const refresh = useCallback(() => {
    if (!deviceId) return
    api
      .mlStatus(deviceId)
      .then((s) => setLoaded({ forDevice: deviceId, status: s }))
      .catch((e) =>
        setLoaded((prev) =>
          prev && prev.forDevice === deviceId && prev.status
            ? prev
            : { forDevice: deviceId, error: e instanceof ApiError ? e.message : 'Failed to load insights' },
        ),
      )
  }, [deviceId])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <main className="min-h-screen bg-slate-900 p-6 text-slate-100">
      <div className="mx-auto max-w-3xl space-y-6">
        <Header />

        <div>
          <h2 className="text-lg font-semibold">ML Insights</h2>
          <p className="text-sm text-slate-400">Occupancy prediction and anomaly detection, learned from your own device's stored readings.</p>
        </div>

        {devices.kind === 'loading' && <p className="text-slate-400">Loading...</p>}
        {devices.kind === 'error' && <p className="rounded bg-red-500/10 p-3 text-sm text-red-400">{devices.message}</p>}
        {devices.kind === 'ready' && devices.devices.length === 0 && (
          <p className="rounded-xl bg-slate-800 p-6 text-center text-sm text-slate-400">
            You have no devices yet. Pair one on the Devices page; insights appear once it has sent readings.
          </p>
        )}

        {devices.kind === 'ready' && devices.devices.length > 0 && (
          <>
            <label className="block text-sm text-slate-300">
              Device
              <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}
                className="mt-1 block w-full max-w-sm rounded-md border border-slate-600 bg-slate-800 px-3 py-2 text-sm">
                {devices.devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </label>

            {!current && <p className="text-slate-400">Loading insights...</p>}
            {current?.error && !current.status && (
              <div className="rounded bg-red-500/10 p-3 text-sm text-red-400">
                {current.error} <button onClick={refresh} className="underline">Try again</button>
              </div>
            )}
            {current?.status && (
              <>
                <p className="rounded-lg bg-slate-800 p-3 text-xs text-slate-400" role="note">{current.status.notice}</p>
                <OccupancyPanel key={deviceId} deviceId={deviceId} status={current.status.occupancy} onChanged={refresh} />
                <AnomalyPanel key={`a-${deviceId}`} deviceId={deviceId} activeCount={current.status.anomalies.active} onChanged={refresh} />
              </>
            )}
          </>
        )}
      </div>
    </main>
  )
}
