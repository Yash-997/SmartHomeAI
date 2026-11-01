import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type KnowledgeDoc } from '../api'

type Load = { docs?: KnowledgeDoc[]; error?: string }

/** Notes the user adds on purpose. Only this user's assistant can search them. */
export default function KnowledgePanel({ maxChars, maxDocs, onChanged }: { maxChars: number; maxDocs: number; onChanged: () => void }) {
  const [load, setLoad] = useState<Load | null>(null)
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const refresh = useCallback(() => {
    api
      .knowledgeDocs()
      .then((docs) => setLoad({ docs }))
      .catch((e) => setLoad({ error: e instanceof ApiError ? e.message : 'Could not load documents' }))
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const add = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.addKnowledgeDoc(title.trim(), text)
      setTitle('')
      setText('')
      refresh()
      onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? (Object.values(err.fieldErrors)[0] ?? err.message) : 'Could not add the document')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (id: string) => {
    setError('')
    try {
      await api.deleteKnowledgeDoc(id)
      refresh()
      onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete the document')
    }
  }

  return (
    <section className="rounded-xl bg-slate-800 p-4" aria-labelledby="knowledge-heading">
      <h3 id="knowledge-heading" className="font-semibold">Your notes</h3>
      <p className="mt-1 text-xs text-slate-400">
        Add text (room notes, a manual excerpt) and the assistant can use it. Only you can search it. Do not paste passwords or keys.
      </p>

      {!load && <p className="mt-3 text-sm text-slate-400">Loading...</p>}
      {load?.error && <p className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400">{load.error} <button onClick={refresh} className="underline">Try again</button></p>}
      {load?.docs && load.docs.length === 0 && <p className="mt-3 text-sm text-slate-500">No notes yet.</p>}
      {load?.docs && load.docs.length > 0 && (
        <ul className="mt-3 space-y-1 text-sm">
          {load.docs.map((d) => (
            <li key={d.id} className="flex items-center justify-between gap-2 rounded bg-slate-900 px-2 py-1.5">
              <span className="truncate" title={d.title}>{d.title} <span className="text-xs text-slate-500">({d.chunks} part{d.chunks === 1 ? '' : 's'})</span></span>
              <button type="button" onClick={() => remove(d.id)} className="text-xs text-red-300 hover:underline" aria-label={`Delete ${d.title}`}>Delete</button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={add} className="mt-3 space-y-2">
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} placeholder="Title" aria-label="Note title" required
          className="w-full rounded-md border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm" />
        <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={maxChars} rows={4} placeholder="Text (at least 20 characters)" aria-label="Note text" required
          className="w-full rounded-md border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm" />
        <div className="flex items-center justify-between">
          <span className="text-xs text-slate-500">{text.length}/{maxChars} · up to {maxDocs} notes</span>
          <button type="submit" disabled={busy || title.trim() === '' || text.trim().length < 20}
            className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
            {busy ? 'Adding...' : 'Add note'}
          </button>
        </div>
        {error && <p className="text-sm text-red-400" role="alert">{error}</p>}
      </form>
    </section>
  )
}
