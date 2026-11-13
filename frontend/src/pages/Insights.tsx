import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device, type MlStatus } from '../api'
import AnomalyPanel from '../components/AnomalyPanel'
import DataCollectionPanel from '../components/DataCollectionPanel'
import OccupancyPanel from '../components/OccupancyPanel'
import PanelBoundary from '../components/PanelBoundary'
import RecommendationsPanel from '../components/RecommendationsPanel'
import InsightsOverview from '../components/InsightsOverview'
import Shell, { PageHeader } from '../components/Shell'
import SyntheticPanel from '../components/SyntheticPanel'
import { normalizeStatus } from '../insights'
import Disclosure from '../ui/Disclosure'

type DevicesLoad = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; devices: Device[] }
type StatusResult = { forDevice: string; error?: string; status?: MlStatus }

export default function Insights() {
  const [devices, setDevices] = useState<DevicesLoad>({ kind: 'loading' })
  const [deviceId, setDeviceId] = useState('')
  const [anomaliesOpen, setAnomaliesOpen] = useState<boolean | null>(null)
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
      .then((s) => setLoaded({ forDevice: deviceId, status: normalizeStatus(s) }))
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

  const status = current?.status
  const selector =
    devices.kind === 'ready' && devices.devices.length > 1 ? (
      <label className="flex items-center gap-2 text-sm text-slate-300">
        Device
        <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)} className="rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-sm">
          {devices.devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </label>
    ) : undefined

  return (
    <Shell wide>
      <PageHeader title="ML Insights" subtitle="What your home has learned from its own stored readings." actions={selector} />

      {devices.kind === 'loading' && <p role="status" className="text-slate-400">Loading...</p>}
      {devices.kind === 'error' && <p role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300">{devices.message}</p>}
      {devices.kind === 'ready' && devices.devices.length === 0 && (
        <p className="card p-8 text-center text-sm text-slate-400">
          You have no devices yet. Pair one on the Devices page; insights appear once it has sent readings.
        </p>
      )}

      {devices.kind === 'ready' && devices.devices.length > 0 && (
        <>
          {!current && <p role="status" className="text-slate-400">Loading insights...</p>}
          {current?.error && !status && (
            <div role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 p-4 text-sm text-red-300">
              {current.error} <button type="button" onClick={refresh} className="underline">Try again</button>
            </div>
          )}

          {status && (
            <>
              <section aria-labelledby="overview-heading" className="space-y-4">
                <h2 id="overview-heading" className="text-lg font-semibold">Overview</h2>
                <InsightsOverview deviceId={deviceId} status={status} onOpenAnomalies={() => setAnomaliesOpen(true)} />
                <PanelBoundary title="Suggestions">
                  <RecommendationsPanel />
                </PanelBoundary>
                <p className="text-xs text-slate-500" role="note">{status.notice}</p>
              </section>

              <section aria-labelledby="advanced-heading" className="space-y-3">
                <div>
                  <h2 id="advanced-heading" className="text-lg font-semibold">Advanced details</h2>
                  <p className="text-sm text-slate-400">Datasets, metrics and history behind the overview. Open a section to dig in.</p>
                </div>
                <Disclosure title="Historical data and connectivity" hint="How much has been stored, and when the device was reachable.">
                  <PanelBoundary title="Historical data">
                    <DataCollectionPanel key={`d-${deviceId}`} deviceId={deviceId} status={status.occupancy} />
                  </PanelBoundary>
                </Disclosure>
                <Disclosure title="Occupancy model: readiness, metrics and training" hint="Requirements, accuracy against a simple baseline, the weekly pattern and past training runs.">
                  <PanelBoundary title="Occupancy prediction">
                    <OccupancyPanel key={deviceId} deviceId={deviceId} status={status.occupancy} onChanged={refresh} />
                  </PanelBoundary>
                </Disclosure>
                <Disclosure title="Anomaly history" hint="Everything the detectors have flagged, with options to acknowledge or dismiss."
                  open={anomaliesOpen ?? status.anomalies.active > 0} onToggle={setAnomaliesOpen}
                  badge={status.anomalies.active > 0 ? <span className="rounded-full bg-amber-400/20 px-2 py-0.5 text-xs font-medium text-amber-200">{status.anomalies.active} active</span> : undefined}>
                  <PanelBoundary title="Anomalies">
                    <AnomalyPanel key={`a-${deviceId}`} deviceId={deviceId} activeCount={status.anomalies.active} onChanged={refresh} />
                  </PanelBoundary>
                </Disclosure>
                <Disclosure title="Synthetic demonstration model" hint="Generated data used only to demonstrate the pipeline. Never your home's real data."
                  badge={<span className="rounded bg-violet-500/25 px-1.5 py-0.5 text-[11px] font-semibold tracking-wide text-violet-200">SYNTHETIC</span>}>
                  <PanelBoundary title="Synthetic demonstration">
                    <SyntheticPanel key={`s-${deviceId}`} deviceId={deviceId} />
                  </PanelBoundary>
                </Disclosure>
              </section>
            </>
          )}
        </>
      )}
    </Shell>
  )
}
