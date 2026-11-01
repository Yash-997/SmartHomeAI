import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Anomaly } from '../api'
import { ANOMALY_STATUS, KIND, SEVERITY, detailPairs, isActive } from '../insights'

type Result = { key: string; items?: Anomaly[]; error?: string }

function AnomalyCard({ deviceId, a, onChanged }: { deviceId: string; a: Anomaly; onChanged: () => void }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)

  const act = async (status: 'ACKNOWLEDGED' | 'RESOLVED' | 'DISMISSED') => {
    setBusy(true)
    setError('')
    try {
      await api.resolveAnomaly(deviceId, a.id, status, note.trim())
      setNote('')
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? (e.fieldErrors.note ?? e.fieldErrors.status ?? e.message) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const pairs = detailPairs(a.details)
  return (
    <li className={`rounded-lg bg-slate-900 p-3 text-sm ${isActive(a.status) ? '' : 'opacity-70'}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${SEVERITY[a.severity].tone}`}>{SEVERITY[a.severity].label}</span>
        <span className="font-medium">{KIND[a.kind]}</span>
        <span className="text-slate-400">{a.scope}</span>
        {a.occurrences > 1 && <span className="text-xs text-slate-500">×{a.occurrences}</span>}
        <span className="ml-auto rounded bg-slate-700 px-2 py-0.5 text-xs">{ANOMALY_STATUS[a.status]}</span>
      </div>
      <p className="mt-2 text-slate-200">{a.explanation}</p>
      <p className="mt-1 text-xs text-slate-500">
        First seen {new Date(a.detectedAt).toLocaleString()} · last seen {new Date(a.lastSeenAt).toLocaleString()}
        {a.resolvedAt ? ` · closed ${new Date(a.resolvedAt).toLocaleString()}` : ''}
      </p>
      {a.resolutionNote && <p className="mt-1 text-xs text-slate-400">Note: {a.resolutionNote}</p>}

      {pairs.length > 0 && (
        <div className="mt-2">
          <button type="button" onClick={() => setOpen(!open)} className="text-xs text-indigo-300 hover:underline" aria-expanded={open}>
            {open ? 'Hide the numbers' : 'Show the numbers behind this'}
          </button>
          {open && (
            <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
              {pairs.map(([k, v]) => (
                <div key={k} className="contents"><dt className="text-slate-500">{k}</dt><dd className="text-slate-300">{v}</dd></div>
              ))}
            </dl>
          )}
        </div>
      )}

      {isActive(a.status) && (
        <div className="mt-3 space-y-2">
          <input aria-label="Note (optional)" value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)"
            className="w-full rounded-md border border-slate-600 bg-slate-800 px-2 py-1.5 text-xs" />
          <div className="flex flex-wrap gap-2">
            {a.status === 'OPEN' && (
              <button disabled={busy} onClick={() => act('ACKNOWLEDGED')} className="rounded-md bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600 disabled:opacity-60">Acknowledge</button>
            )}
            <button disabled={busy} onClick={() => act('RESOLVED')} className="rounded-md bg-green-700/70 px-3 py-1 text-xs hover:bg-green-700 disabled:opacity-60">Mark resolved</button>
            <button disabled={busy} onClick={() => act('DISMISSED')} className="rounded-md bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600 disabled:opacity-60">Dismiss as false alarm</button>
          </div>
          {error && <p className="text-xs text-red-400">{error}</p>}
        </div>
      )}
    </li>
  )
}

export default function AnomalyPanel({ deviceId, activeCount, onChanged }: { deviceId: string; activeCount: number; onChanged: () => void }) {
  const [filter, setFilter] = useState<'active' | 'all'>('active')
  const [result, setResult] = useState<Result | null>(null)
  const key = `${deviceId}:${filter}`
  // Loading is derived: a result only counts if it was loaded for the current device and filter.
  const load = result && result.key === key ? result : null
  const [scanning, setScanning] = useState(false)
  const [scanNote, setScanNote] = useState('')

  const refresh = useCallback(() => {
    api
      .anomalies(deviceId, filter)
      .then((items) => setResult({ key: `${deviceId}:${filter}`, items }))
      .catch((e) =>
        setResult((prev) =>
          prev && prev.key === `${deviceId}:${filter}` && prev.items
            ? prev
            : { key: `${deviceId}:${filter}`, error: e instanceof ApiError ? e.message : 'Could not load anomalies' },
        ),
      )
  }, [deviceId, filter])

  useEffect(() => {
    refresh()
    const poll = setInterval(refresh, 30000)
    return () => clearInterval(poll)
  }, [refresh])

  const changed = () => {
    refresh()
    onChanged()
  }

  const scan = async () => {
    setScanning(true)
    setScanNote('')
    try {
      const r = await api.scanAnomalies(deviceId)
      setScanNote(r.findings > 0 ? `Check finished: ${r.findings} finding(s) recorded or updated.` : 'Check finished: nothing unusual found.')
      changed()
    } catch (e) {
      setScanNote(e instanceof ApiError ? e.message : 'The check failed')
    } finally {
      setScanning(false)
    }
  }

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="anomaly-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="anomaly-heading" className="font-semibold">
          Anomalies <span className="text-sm font-normal text-slate-400">({activeCount} active)</span>
        </h3>
        <div className="flex items-center gap-2">
          <div className="flex overflow-hidden rounded-md text-xs" role="group" aria-label="Filter">
            {(['active', 'all'] as const).map((f) => (
              <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)}
                className={`px-3 py-1.5 ${filter === f ? 'bg-indigo-600' : 'bg-slate-700 hover:bg-slate-600'}`}>
                {f === 'active' ? 'Active' : 'History'}
              </button>
            ))}
          </div>
          <button type="button" onClick={scan} disabled={scanning} className="rounded-md bg-slate-700 px-3 py-1.5 text-xs hover:bg-slate-600 disabled:opacity-60">
            {scanning ? 'Checking...' : 'Check now'}
          </button>
        </div>
      </div>
      <p className="mt-1 text-xs text-slate-500">
        Readings are compared with this device's own recent normal (median and spread, by time of day). Missing, frozen, impossible and silent sensors are reported too. Checks run about once a minute.
      </p>
      {scanNote && <p className="mt-2 text-xs text-slate-300" role="status">{scanNote}</p>}

      {!load && <p className="mt-3 text-sm text-slate-400">Loading...</p>}
      {load?.error && !load.items && (
        <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400">{load.error} <button onClick={refresh} className="underline">Try again</button></p>
      )}
      {load?.items && load.items.length === 0 && (
        <p className="mt-3 rounded-lg bg-slate-900 p-4 text-center text-sm text-slate-400">
          {filter === 'active' ? 'No active anomalies. Everything looks normal for this device.' : 'No anomalies have been recorded for this device yet.'}
        </p>
      )}
      {load?.items && load.items.length > 0 && (
        <ul className="mt-3 space-y-2">
          {load.items.map((a) => <AnomalyCard key={a.id} deviceId={deviceId} a={a} onChanged={changed} />)}
        </ul>
      )}
    </section>
  )
}
