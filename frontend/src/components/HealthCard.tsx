import { useEffect, useState } from 'react'
import { api } from '../api'

type State = { kind: 'loading' } | { kind: 'up'; service: string; status: string } | { kind: 'down'; error: string }

async function fetchHealth(): Promise<State> {
  try {
    const h = await api.health()
    return { kind: 'up', service: h.service, status: h.status }
  } catch (e) {
    return { kind: 'down', error: e instanceof Error ? e.message : 'Unknown error' }
  }
}

export default function HealthCard() {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    fetchHealth().then(setState)
  }, [])

  const dot =
    state.kind === 'up' ? 'bg-green-500' : state.kind === 'down' ? 'bg-red-500' : 'bg-yellow-400 animate-pulse'

  return (
    <div className="rounded-xl bg-slate-800 p-6 shadow-lg">
      <h2 className="font-semibold">Backend health</h2>
      <div className="mt-3 flex items-center gap-3 text-sm">
        <span className={`h-3 w-3 rounded-full ${dot}`} />
        {state.kind === 'loading' && 'Checking...'}
        {state.kind === 'up' && `${state.service} is ${state.status}`}
        {state.kind === 'down' && <span className="text-red-400">{state.error}</span>}
      </div>
      <button
        type="button"
        onClick={() => {
          setState({ kind: 'loading' })
          fetchHealth().then(setState)
        }}
        className="mt-4 rounded-md bg-slate-700 px-3 py-1.5 text-sm hover:bg-slate-600"
      >
        Re-check
      </button>
    </div>
  )
}
