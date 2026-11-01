import { AnimatePresence, m } from 'motion/react'
import { dismissToast, useToasts } from './toastStore'

/** Bottom-corner messages for things that just happened. Errors are announced assertively, successes politely. */
export default function ToastHost() {
  const toasts = useToasts()
  return (
    <div className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col items-end gap-2 sm:inset-x-auto sm:right-6 sm:bottom-6">
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <m.div
            key={t.id}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 8, transition: { duration: 0.15 } }}
            transition={{ duration: 0.2, ease: [0.2, 0.7, 0.2, 1] }}
            role={t.kind === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-lg border bg-slate-800 px-4 py-3 text-sm shadow-lg sm:w-auto ${
              t.kind === 'error' ? 'border-red-500/50' : 'border-indigo-400/40'
            }`}
          >
            <span aria-hidden="true" className={`mt-1 h-2 w-2 shrink-0 rounded-full ${t.kind === 'error' ? 'bg-red-400' : 'bg-indigo-400'}`} />
            <span className="flex-1 text-slate-100">{t.text}</span>
            <button type="button" onClick={() => dismissToast(t.id)} aria-label="Dismiss message" className="text-slate-400 hover:text-slate-100">
              &times;
            </button>
          </m.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
