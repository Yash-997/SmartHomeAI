import { useEffect, useState } from 'react'
import { api, ApiError, type Connectivity, type OccupancyStatus } from '../api'
import { DISPLAY_TZ, fmtCount, fmtDate, fmtDateTime } from '../insights'

interface Props {
  deviceId: string
  status: OccupancyStatus
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="min-w-0 rounded-lg bg-slate-900 p-3">
      <div className="text-[11px] uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-0.5 truncate text-lg font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-[11px] text-slate-500">{hint}</div>}
    </div>
  )
}

/** What real history has been stored for this device, and when it was online. Nothing here is estimated. */
export default function DataCollectionPanel({ deviceId, status }: Props) {
  const d = status.data
  const ds = d.dataset   // null when the server did not report it: shown as unknown, never guessed
  const [conn, setConn] = useState<{ forDevice: string; data?: Connectivity; error?: string } | null>(null)
  const shown = conn && conn.forDevice === deviceId ? conn : null

  useEffect(() => {
    let cancelled = false
    api
      .connectivity(deviceId, 8)
      .then((c) => !cancelled && setConn({ forDevice: deviceId, data: c }))
      .catch((e) => !cancelled && setConn({ forDevice: deviceId, error: e instanceof ApiError ? e.message : 'Could not load connectivity' }))
    return () => {
      cancelled = true
    }
  }, [deviceId, status])

  // Measured against the server's check time, so the figure does not depend on the browser clock.
  const days = d.collectionStartedAt && ds ? Math.max(0, Math.floor((new Date(ds.checkedAt).getTime() - new Date(d.collectionStartedAt).getTime()) / 86_400_000)) : null

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="collection-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="collection-heading" className="font-semibold">Historical data <span className="ml-1 rounded bg-emerald-500/15 px-1.5 py-0.5 align-middle text-[11px] font-medium text-emerald-300">Source: real ESP32</span></h3>
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${ds?.enoughForTraining ? 'bg-green-500/20 text-green-300' : 'bg-amber-500/20 text-amber-300'}`}>
          {ds?.enoughForTraining ? 'Enough to train' : 'Collecting historical data'}
        </span>
      </div>
      <p className="mt-1 text-xs text-slate-400">
        Every message the device sends is stored and never overwritten. Training uses only these real readings. Times in {DISPLAY_TZ}.
      </p>

      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label="Records stored" value={fmtCount(d.totalRecords)} hint={d.recordsLast24h === null ? undefined : `${fmtCount(d.recordsLast24h)} in the last 24 h`} />
        <Stat label="Collecting since" value={fmtDate(d.collectionStartedAt ?? d.firstAt)} hint={days !== null ? `${days} day${days === 1 ? '' : 's'}` : d.collectionStartedAt || d.firstAt ? undefined : 'no data yet'} />
        <Stat label="Motion readings" value={fmtCount(d.motionReadings)} hint={d.lastAt ? `latest ${fmtDateTime(d.lastAt)}` : 'none yet'} />
        <Stat label="Training slots" value={fmtCount(ds?.usableSlots)} hint={ds ? `${ds.distinctDays} day${ds.distinctDays === 1 ? '' : 's'} · ${status.requirements.slotMinutes}-min slots` : 'not reported'} />
      </div>
      {ds ? (
        <p className="mt-2 text-[11px] text-slate-500">
          A training slot is a {status.requirements.slotMinutes}-minute window with at least {status.requirements.minReadingsPerSlot} readings ({ds.slotsWithMotion} with motion, {ds.slotsWithoutMotion} without).
          {ds.sparseSlotsIgnored > 0 && ` ${fmtCount(ds.sparseSlotsIgnored)} windows had too few readings and are ignored, never counted as "empty".`}
          {' '}Checked {fmtDateTime(ds.checkedAt)}.
        </p>
      ) : (
        <p className="mt-2 text-[11px] text-slate-500">The server did not report the training dataset. Rebuild the backend to see collection details.</p>
      )}

      <div className="mt-4">
        <h4 className="text-sm font-medium">Connectivity</h4>
        {!shown && <p className="mt-1 text-xs text-slate-400">Loading...</p>}
        {shown?.error && <p className="mt-1 text-xs text-red-400">{shown.error}</p>}
        {shown?.data && (
          <>
            <p className="mt-1 text-xs text-slate-400">
              Now <strong className={shown.data.currentState === 'ONLINE' ? 'text-green-300' : 'text-amber-300'}>{shown.data.currentState.replace('_', ' ').toLowerCase()}</strong>
              {' · '}{shown.data.offlineEventsLast7Days} disconnection{shown.data.offlineEventsLast7Days === 1 ? '' : 's'} in the last 7 days
            </p>
            {shown.data.events.length === 0 ? (
              <p className="mt-1 text-xs text-slate-500">No changes recorded yet. Changes are checked once a minute.</p>
            ) : (
              <ul className="mt-2 space-y-1 text-xs">
                {shown.data.events.map((e) => (
                  <li key={`${e.changedAt}-${e.state}`} className="flex flex-wrap gap-x-2">
                    <span className={`w-16 font-medium ${e.state === 'ONLINE' ? 'text-green-300' : 'text-amber-300'}`}>{e.state.toLowerCase()}</span>
                    <span className="tabular-nums text-slate-300">{fmtDateTime(e.changedAt)}</span>
                    <span className="text-slate-500">{e.reason}</span>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </section>
  )
}
