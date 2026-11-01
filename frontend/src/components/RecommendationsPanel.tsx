import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Recommendation } from '../api'
import { notify } from '../ui/toastStore'
import { PanelSkeleton } from '../ui/Skeleton'
import { usePolled } from './usePolled'

function Card({ r, onDone }: { r: Recommendation; onDone: (id: string, outcome: 'applied' | 'dismissed') => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)

  const act = async (kind: 'approve' | 'dismiss') => {
    setBusy(true)
    setError('')
    try {
      if (kind === 'approve') await api.approveRecommendation(r.id)
      else await api.dismissRecommendation(r.id)
      onDone(r.id, kind === 'approve' ? 'applied' : 'dismissed')
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fieldErrors)[0] ?? e.message) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="rounded-lg bg-slate-900 p-3 text-sm">
      <p className="font-medium">{r.title}</p>
      <p className="mt-1 text-slate-300">{r.rationale}</p>
      <p className="mt-1 text-xs text-slate-400">If you approve: {r.description}</p>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="mt-1 text-xs text-indigo-300 hover:underline">
        {open ? 'Hide the numbers' : 'Show the numbers behind this'}
      </button>
      {open && (
        <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 text-xs">
          {Object.entries(r.evidence).map(([k, v]) => (
            <div key={k} className="contents"><dt className="text-slate-500">{k}</dt><dd className="text-slate-300">{String(v)}</dd></div>
          ))}
        </dl>
      )}
      <p className="mt-2 text-xs text-slate-500">Nothing changes until you approve.</p>
      <div className="mt-2 flex gap-2">
        <button type="button" disabled={busy} onClick={() => act('approve')} className="rounded-md bg-green-700 px-3 py-1.5 text-xs font-medium hover:bg-green-600 disabled:opacity-60">
          {r.actionType === 'DISABLE_RULE' ? 'Approve and disable the rule' : 'Approve and create rule'}
        </button>
        <button type="button" disabled={busy} onClick={() => act('dismiss')} className="rounded-md bg-slate-700 px-3 py-1.5 text-xs hover:bg-slate-600 disabled:opacity-60">Dismiss</button>
      </div>
      {error && <p className="mt-2 text-xs text-red-400" role="alert">{error}</p>}
    </li>
  )
}

export default function RecommendationsPanel() {
  const { data, error, reload, set } = usePolled(api.recommendations, 60000)
  const [busy, setBusy] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const [lastOutcome, setLastOutcome] = useState('')

  const refresh = async () => {
    setBusy(true)
    setRefreshError('')
    try {
      set(await api.refreshRecommendations())
    } catch (e) {
      setRefreshError(e instanceof ApiError ? e.message : 'Could not refresh')
    } finally {
      setBusy(false)
    }
  }

  const done = (id: string, outcome: 'applied' | 'dismissed') => {
    setLastOutcome(outcome === 'applied' ? 'Applied. See it on the Automation page.' : 'Dismissed. It will not come back for two weeks.')
    notify(outcome === 'applied' ? 'Recommendation applied' : 'Recommendation dismissed')
    if (data) set({ ...data, recommendations: data.recommendations.filter((r) => r.id !== id) })
    reload()
  }

  return (
    <section className="rounded-xl bg-slate-800 p-5 shadow-lg" aria-labelledby="rec-heading">
      <div className="flex items-center justify-between">
        <h3 id="rec-heading" className="font-semibold">Recommendations</h3>
        <button type="button" onClick={refresh} disabled={busy} className="rounded-md bg-slate-700 px-3 py-1 text-xs hover:bg-slate-600 disabled:opacity-60">{busy ? 'Looking...' : 'Look again'}</button>
      </div>
      <p className="mt-1 text-xs text-slate-500">Built from your own readings, occupancy model, anomalies and rule history. Each shows its numbers; none is applied without your approval.</p>

      {!data && !error && <PanelSkeleton lines={3} />}
      {error && !data && <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error} <button onClick={reload} className="underline">Try again</button></p>}
      {refreshError && <p className="mt-2 text-sm text-red-400" role="alert">{refreshError}</p>}
      {lastOutcome && <p className="mt-2 text-xs text-green-300" role="status">{lastOutcome} <Link to="/automation" className="underline">Automation</Link></p>}

      {data && data.recommendations.length === 0 && <p className="mt-3 rounded-lg bg-slate-900 p-3 text-sm text-slate-300">No recommendations right now.</p>}
      {data && data.recommendations.length > 0 && (
        <ul className="mt-3 space-y-3">
          {data.recommendations.map((r) => <Card key={r.id} r={r} onDone={done} />)}
        </ul>
      )}
      {data && data.notes.length > 0 && (
        <details className="mt-3 text-xs text-slate-400">
          <summary className="cursor-pointer">Why not more? ({data.notes.length})</summary>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">{data.notes.map((n) => <li key={n}>{n}</li>)}</ul>
        </details>
      )}
    </section>
  )
}
