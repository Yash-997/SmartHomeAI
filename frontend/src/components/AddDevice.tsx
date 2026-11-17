import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { AnimatePresence, m } from 'motion/react'
import { api, ApiError, type PairingCode } from '../api'
import { LogoMark } from '../ui/Logo'
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

const STEPS = ['Name', 'Pair', 'Done'] as const

/** A ring that empties as the code's real expiry time (from the backend) approaches. */
function CountdownRing({ left, total }: { left: number; total: number }) {
  const r = 26
  const c = 2 * Math.PI * r
  const frac = total > 0 ? left / total : 0
  const tone = left <= 60 ? '#D9535D' : left <= 180 ? '#D99A35' : '#713B50'
  return (
    <svg viewBox="0 0 64 64" width="64" height="64" aria-hidden="true" className="-rotate-90">
      <circle cx="32" cy="32" r={r} fill="none" stroke="#F2E9ED" strokeWidth="4" />
      <circle cx="32" cy="32" r={r} fill="none" stroke={tone} strokeWidth="4" strokeLinecap="round" strokeDasharray={c}
        strokeDashoffset={c * (1 - frac)} style={{ transition: 'stroke-dashoffset 1s linear, stroke 300ms ease' }} />
    </svg>
  )
}

const panel = {
  initial: { opacity: 0, x: 18 },
  animate: { opacity: 1, x: 0, transition: { duration: 0.28, ease: [0.2, 0.7, 0.2, 1] as const } },
  exit: { opacity: 0, x: -18, transition: { duration: 0.15 } },
}

export default function AddDevice({ knownIds, onClose, onPaired }: Props) {
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [code, setCode] = useState<PairingCode | null>(null)
  const [left, setLeft] = useState(0)
  const [paired, setPaired] = useState(false)
  const [copied, setCopied] = useState(false)
  const known = useRef(knownIds)
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)

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

  // Escape closes; focus starts inside the dialog. The latest onClose is read through a ref so that a parent passing
  // a new function each render does not re-run this (and steal focus) every second.
  const closeRef = useRef(onClose)
  useEffect(() => {
    closeRef.current = onClose
  })
  useEffect(() => {
    dialogRef.current?.querySelector<HTMLElement>('input')?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && closeRef.current()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const expired = code !== null && left === 0 && !paired
  const step = paired ? 2 : code ? 1 : 0

  const copy = async () => {
    if (!code) return
    try {
      await navigator.clipboard.writeText(code.code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      // Clipboard can be unavailable (http, permissions); the code stays visible to type by hand.
    }
  }

  return (
    <m.div className="fixed inset-0 z-40 flex items-center justify-center p-4 sm:p-6" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.2 }}>
      <button type="button" aria-label="Close" tabIndex={-1} onClick={onClose} className="absolute inset-0 bg-[#292b35]/35 backdrop-blur-sm" />
      <m.div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="card relative w-full max-w-md overflow-hidden p-6 sm:p-7"
        initial={{ opacity: 0, y: 16, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.3, ease: [0.2, 0.7, 0.2, 1] }}
      >
        <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-linear-to-r from-transparent via-indigo-300/60 to-transparent" />
        <div className="flex items-center gap-3">
          <LogoMark size={36} />
          <div>
            <h2 id={titleId} className="text-lg font-semibold">Add a device</h2>
            <p className="text-xs text-slate-400">Pair an ESP32 with your VÉYRA home</p>
          </div>
        </div>

        <ol className="mt-5 flex items-center gap-2" aria-label="Progress">
          {STEPS.map((s, i) => (
            <li key={s} className="flex flex-1 items-center gap-2" aria-current={i === step ? 'step' : undefined}>
              <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-semibold transition-colors duration-300 ${
                i < step ? 'bg-green-500 text-white' : i === step ? 'bg-indigo-400 text-white' : 'bg-slate-700 text-slate-400'
              }`}>{i < step ? '✓' : i + 1}</span>
              <span className={`text-xs ${i === step ? 'text-slate-100' : 'text-slate-500'}`}>{s}</span>
              {i < STEPS.length - 1 && <span aria-hidden="true" className={`h-px flex-1 transition-colors duration-300 ${i < step ? 'bg-green-500/60' : 'bg-slate-700'}`} />}
            </li>
          ))}
        </ol>

        <div className="mt-6">
          <AnimatePresence mode="wait" initial={false}>
            {!code && (
              <m.form key="name" {...panel} onSubmit={submit} noValidate className="space-y-4">
                <Field id="device-name" label="Device name" placeholder="e.g. Living room sensor" maxLength={60}
                  value={name} onChange={(e) => setName(e.target.value)} error={error || undefined} />
                <div className="flex justify-end gap-2 pt-1">
                  <button type="button" onClick={onClose} className="btn-secondary rounded-lg px-4 py-2 text-sm">Cancel</button>
                  <button disabled={busy} className="btn-primary flex items-center gap-2 rounded-lg px-4 py-2 text-sm">
                    {busy && <span className="spinner" aria-hidden="true" />}
                    {busy ? 'Generating...' : 'Generate pairing code'}
                  </button>
                </div>
              </m.form>
            )}

            {code && !paired && (
              <m.div key="code" {...panel} className="space-y-4 text-sm">
                <div className={`rounded-xl border p-4 ${expired ? 'border-red-400/40 bg-red-500/10' : 'border-slate-700 bg-slate-900'}`}>
                  <div className="flex items-center justify-between gap-4">
                    <div className="min-w-0">
                      <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-500">One-time code</p>
                      <p className={`mt-1 font-mono text-3xl font-semibold tracking-[0.2em] ${expired ? 'text-slate-500 line-through' : ''}`} data-testid="pairing-code">
                        {code.code}
                      </p>
                    </div>
                    <div className="relative grid place-items-center">
                      <CountdownRing left={left} total={code.expiresInSeconds} />
                      <span className="absolute font-mono text-[11px] text-slate-300">{Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}</span>
                    </div>
                  </div>
                  {!expired && (
                    <button type="button" onClick={copy} className="mt-3 text-xs font-medium text-indigo-300 hover:text-indigo-200">
                      {copied ? 'Copied ✓' : 'Copy code'}
                    </button>
                  )}
                </div>

                {expired ? (
                  <p role="alert" className="text-red-300">This code has expired. Close and generate a new one.</p>
                ) : (
                  <>
                    <ol className="space-y-2 text-slate-300">
                      <li className="flex gap-2"><span className="text-indigo-300">1.</span>Open the Serial Monitor at 115200 baud with the ESP32 connected.</li>
                      <li className="flex gap-2"><span className="text-indigo-300">2.</span>Type this code when the device asks for it and press Enter.</li>
                      <li className="flex gap-2"><span className="text-indigo-300">3.</span>Keep this window open; it notices the new device by itself.</li>
                    </ol>
                    <div className="flex items-center gap-2 text-slate-400" role="status">
                      <span className="flex gap-1" aria-hidden="true"><span className="typing-dot" /><span className="typing-dot" /><span className="typing-dot" /></span>
                      Waiting for the device to pair... expires in {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}
                    </div>
                  </>
                )}
                <div className="flex justify-end">
                  <button type="button" onClick={onClose} className="btn-secondary rounded-lg px-4 py-2">Close</button>
                </div>
              </m.div>
            )}

            {paired && (
              <m.div key="done" {...panel} className="space-y-4 text-center text-sm">
                <m.div className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-green-500/15 text-green-300"
                  initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ type: 'spring', stiffness: 300, damping: 18 }}>
                  <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <m.path d="M5 12.5l4.5 4.5L19 7.5" initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 0.45, delay: 0.15 }} />
                  </svg>
                </m.div>
                <p role="status" className="text-base font-semibold text-green-300">Device paired</p>
                <p className="text-slate-400">It will show as "Not connected" until it connects to the broker.</p>
                <div className="flex justify-center">
                  <button type="button" onClick={onClose} className="btn-primary rounded-lg px-5 py-2">Done</button>
                </div>
              </m.div>
            )}
          </AnimatePresence>
        </div>
      </m.div>
    </m.div>
  )
}
