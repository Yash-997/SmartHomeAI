import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type PairingCode } from '../api'
import Field from './Field'

interface Props {
  /** Ids of devices already listed, used to detect the new device appearing after pairing. */
  knownIds: string[]
  onClose: () => void
  onPaired: () => void
}

function secondsLeft(expiresAt: string) {
  return Math.max(0, Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000))
}

export default function AddDevice({ knownIds, onClose, onPaired }: Props) {
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [code, setCode] = useState<PairingCode | null>(null)
  const [left, setLeft] = useState(0)
  const [paired, setPaired] = useState(false)
  const known = useRef(knownIds)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return setError('Give the device a name')
    setBusy(true)
    setError('')
    try {
      const c = await api.createPairingCode(name.trim())
      setCode(c)
      setLeft(secondsLeft(c.expiresAt))
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors.name ?? err.message) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  // Countdown, and poll for the device showing up once the code has been used.
  useEffect(() => {
    if (!code || paired) return
    const tick = setInterval(() => setLeft(secondsLeft(code.expiresAt)), 1000)
    const poll = setInterval(() => {
      api
        .listDevices()
        .then((list) => {
          if (list.some((d) => !known.current.includes(d.id))) {
            setPaired(true)
            onPaired()
          }
        })
        .catch(() => {})
    }, 3000)
    return () => {
      clearInterval(tick)
      clearInterval(poll)
    }
  }, [code, paired, onPaired])

  const expired = code !== null && left === 0 && !paired

  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/60 p-6" role="dialog" aria-modal="true">
      <div className="w-full max-w-md space-y-4 rounded-xl bg-slate-800 p-6 shadow-lg">
        <h2 className="text-lg font-semibold">Add a device</h2>

        {!code && (
          <form onSubmit={submit} noValidate className="space-y-4">
            <Field id="device-name" label="Device name" placeholder="e.g. Living room sensor" maxLength={60}
              value={name} onChange={(e) => setName(e.target.value)} />
            {error && <p className="text-sm text-red-400">{error}</p>}
            <div className="flex justify-end gap-2">
              <button type="button" onClick={onClose} className="rounded-md bg-slate-700 px-3 py-1.5 text-sm hover:bg-slate-600">Cancel</button>
              <button disabled={busy} className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm hover:bg-indigo-500 disabled:opacity-60">
                {busy ? 'Generating...' : 'Generate pairing code'}
              </button>
            </div>
          </form>
        )}

        {code && !paired && (
          <div className="space-y-3 text-sm">
            <p className="text-slate-300">Enter this code on your ESP32 during setup. It works once.</p>
            <p className="rounded-md bg-slate-900 py-4 text-center font-mono text-3xl tracking-widest" data-testid="pairing-code">
              {code.code}
            </p>
            {expired ? (
              <p className="text-red-400">This code has expired. Close and generate a new one.</p>
            ) : (
              <p className="text-slate-400">
                Expires in {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}. Waiting for the device to pair...
              </p>
            )}
            <div className="flex justify-end">
              <button onClick={onClose} className="rounded-md bg-slate-700 px-3 py-1.5 hover:bg-slate-600">Close</button>
            </div>
          </div>
        )}

        {paired && (
          <div className="space-y-3 text-sm">
            <p className="text-green-400">Device paired. It will show as "Not connected" until it connects.</p>
            <div className="flex justify-end">
              <button onClick={onClose} className="rounded-md bg-indigo-600 px-3 py-1.5 hover:bg-indigo-500">Done</button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
