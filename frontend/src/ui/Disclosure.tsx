import { useState, type ReactNode } from 'react'
import { ChevronDownIcon } from './icons'

interface Props {
  title: string
  /** One line shown under the title. */
  hint?: string
  /** Small status chip on the right of the title row. */
  badge?: ReactNode
  defaultOpen?: boolean
  /** Controlled mode: when given, the parent decides. */
  open?: boolean
  onToggle?: (open: boolean) => void
  children: ReactNode
}

/** A collapsible card (native <details>, so Enter/Space and screen readers work without extra code). */
export default function Disclosure({ title, hint, badge, defaultOpen = false, open, onToggle, children }: Props) {
  const [inner, setInner] = useState(defaultOpen)
  const isOpen = open ?? inner
  return (
    <details
      open={isOpen}
      onToggle={(e) => {
        const v = e.currentTarget.open
        if (v !== isOpen) {
          setInner(v)
          onToggle?.(v)
        }
      }}
      className="card group"
    >
      <summary className="flex cursor-pointer list-none items-center gap-3 rounded-2xl p-5 marker:hidden hover:bg-slate-700/30 [&::-webkit-details-marker]:hidden">
        <span className="min-w-0 flex-1">
          <span className="block font-semibold">{title}</span>
          {hint && <span className="mt-0.5 block text-xs text-slate-400">{hint}</span>}
        </span>
        {badge}
        <ChevronDownIcon aria-hidden="true" className="shrink-0 text-slate-400 transition-transform group-open:rotate-180" />
      </summary>
      <div className="flat-panel px-5 pb-5">{children}</div>
    </details>
  )
}
