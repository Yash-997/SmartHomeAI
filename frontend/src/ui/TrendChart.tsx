import { useState } from 'react'
import { m } from 'motion/react'

export interface TrendPoint {
  /** ISO timestamp of the reading. */
  at: string
  value: number
}

interface Props {
  /** Oldest first. */
  points: TrendPoint[]
  label: string
  unit: string
  decimals?: number
  height?: number
}

const stamp = (iso: string) => new Date(iso).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
const clock = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

/**
 * A compact line chart with a value axis (low / middle / high) and a time axis (first / middle / last reading).
 * It draws only what was measured; with fewer than three readings it draws nothing.
 */
export default function TrendChart({ points, label, unit, decimals = 1, height = 112 }: Props) {
  const [hover, setHover] = useState<number | null>(null)
  if (points.length < 3) return null
  const values = points.map((p) => p.value)
  let min = Math.min(...values)
  let max = Math.max(...values)
  if (max - min < 0.5) {
    // A flat line would otherwise fill the whole box; give it a little room around the value.
    min -= 0.5
    max += 0.5
  }
  const span = max - min
  const W = 300
  const H = 100
  const x = (i: number) => (i / (points.length - 1)) * W
  const y = (v: number) => H - ((v - min) / span) * H
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ')
  const area = `${line} L${W} ${H} L0 ${H} Z`
  const last = values[values.length - 1]
  const mid = (min + max) / 2
  const fmt = (v: number) => v.toFixed(decimals)
  const summary = `${label}, last ${points.length} readings: lowest ${fmt(Math.min(...values))} ${unit}, highest ${fmt(Math.max(...values))} ${unit}, latest ${fmt(last)} ${unit}.`
  const gid = `fill-${label.replace(/\W/g, '')}`

  return (
    <figure className="m-0">
      <div className="flex gap-2">
        <div aria-hidden="true" className="flex flex-col justify-between py-0.5 text-right text-xs leading-none tabular-nums text-slate-400" style={{ height }}>
          <span>{fmt(max)}</span>
          <span>{fmt(mid)}</span>
          <span>{fmt(min)}</span>
        </div>
        <div className="relative min-w-0 flex-1 touch-pan-y outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/60" style={{ height }} tabIndex={0}
          onPointerMove={(e) => {
            const r = e.currentTarget.getBoundingClientRect()
            setHover(Math.max(0, Math.min(points.length - 1, Math.round(((e.clientX - r.left) / r.width) * (points.length - 1)))))
          }}
          onPointerLeave={() => setHover(null)}
          onBlur={() => setHover(null)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowLeft') setHover((h) => Math.max(0, (h ?? points.length - 1) - 1))
            else if (e.key === 'ArrowRight') setHover((h) => Math.min(points.length - 1, (h ?? points.length - 1) + 1))
            else if (e.key === 'Escape') setHover(null)
          }}>
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={summary} className="h-full w-full overflow-visible">
            <defs>
              <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-indigo-400)" stopOpacity="0.22" />
                <stop offset="100%" stopColor="var(--color-indigo-400)" stopOpacity="0" />
              </linearGradient>
            </defs>
            {[0, 50, 100].map((g) => (
              <line key={g} x1="0" x2={W} y1={g} y2={g} stroke="var(--color-slate-700)" strokeWidth="1" vectorEffect="non-scaling-stroke" strokeDasharray={g === 100 ? undefined : '3 4'} />
            ))}
            <m.path d={area} fill={`url(#${gid})`} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.4, delay: 0.2 }} />
            <m.path
              d={line}
              fill="none"
              stroke="var(--color-indigo-400)"
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
              strokeLinecap="round"
              strokeLinejoin="round"
              initial={{ pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.7, ease: [0.2, 0.7, 0.2, 1] }}
            />
          </svg>
          <span
            aria-hidden="true"
            className="absolute h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-800 bg-indigo-400"
            style={{ left: '100%', top: `${(y(last) / H) * 100}%` }}
          />
          {hover !== null && (
            <>
              <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 w-px bg-slate-500/60" style={{ left: `${(hover / (points.length - 1)) * 100}%` }} />
              <span aria-hidden="true" className="pointer-events-none absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-800 bg-indigo-300"
                style={{ left: `${(hover / (points.length - 1)) * 100}%`, top: `${(y(values[hover]) / H) * 100}%` }} />
              <span role="status" className="pointer-events-none absolute -top-1 z-10 -translate-y-full whitespace-nowrap rounded-lg bg-slate-700 px-2.5 py-1.5 text-xs shadow-lg"
                style={{ left: `${(hover / (points.length - 1)) * 100}%`, transform: `translate(${hover > points.length * 0.7 ? '-100%' : hover < points.length * 0.3 ? '0' : '-50%'}, -100%)` }}>
                <span className="font-semibold text-slate-100">{fmt(values[hover])} {unit}</span>
                <span className="ml-2 text-slate-400">{stamp(points[hover].at)}</span>
              </span>
            </>
          )}
        </div>
      </div>
      <figcaption aria-hidden="true" className="mt-2 flex justify-between pl-10 text-xs tabular-nums text-slate-400">
        <span>{clock(points[0].at)}</span>
        <span>{clock(points[Math.floor((points.length - 1) / 2)].at)}</span>
        <span>{clock(points[points.length - 1].at)}</span>
      </figcaption>
    </figure>
  )
}
