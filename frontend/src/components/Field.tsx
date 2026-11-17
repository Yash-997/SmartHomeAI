import { useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { AnimatePresence, m } from 'motion/react'

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  /** An optional icon shown inside the field, on the left. */
  icon?: ReactNode
  /** Small helper text under the field (replaced by the error when there is one). */
  hint?: string
}

function Eye({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />
      {!open && <path d="M4 4l16 16" />}
    </svg>
  )
}

/** A labelled text input. Password fields get a show/hide toggle; errors are announced and linked to the input. */
export default function Field({ label, error, id, icon, hint, type = 'text', ...input }: Props) {
  const [reveal, setReveal] = useState(false)
  const isPassword = type === 'password'
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined

  return (
    <div>
      <label htmlFor={id} className="block text-[13px] font-medium text-slate-300">
        {label}
      </label>
      <div className="relative mt-1.5">
        {icon && <span aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-500">{icon}</span>}
        <input
          id={id}
          type={isPassword && reveal ? 'text' : type}
          {...input}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={`field-input w-full rounded-lg py-2.5 text-sm text-slate-100 placeholder:text-slate-500 ${icon ? 'pl-10' : 'pl-3'} ${isPassword ? 'pr-11' : 'pr-3'}`}
        />
        {isPassword && (
          <button
            type="button"
            onClick={() => setReveal((v) => !v)}
            aria-label={reveal ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
            aria-pressed={reveal}
            aria-controls={id}
            className="absolute right-1.5 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-md text-slate-400 hover:bg-slate-700 hover:text-slate-100"
          >
            <Eye open={reveal} />
          </button>
        )}
      </div>
      <AnimatePresence initial={false} mode="wait">
        {error ? (
          <m.p key="e" id={`${id}-error`} role="alert" className="mt-1.5 text-xs text-red-300"
            initial={{ opacity: 0, y: -3 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.16 }}>
            {error}
          </m.p>
        ) : hint ? (
          <m.p key="h" id={`${id}-hint`} className="mt-1.5 text-xs text-slate-500" initial={false} animate={{ opacity: 1 }}>{hint}</m.p>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
