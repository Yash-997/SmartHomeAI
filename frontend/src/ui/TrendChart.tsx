import { useId, useMemo, useState } from 'react'
import { m, useReducedMotion } from 'motion/react'
import { segmentPoints, type TrendPoint, type Valid } from './trendSegments'

export type { TrendPoint } from './trendSegments'

interface Props {
  /** Any order; the chart sorts them by time. */
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
 * Points sit at their real time; it draws only what was measured, leaves gaps open, and with fewer than three
 * readings it draws nothing.
 */
export default function TrendChart({ points, label, unit, decimals = 1, height = 112 }: Props) {
  const [hover, setHover] = useState<number | null>(null)
  const reduce = useReducedMotion()
  const uid = useId().replace(/:/g, '')
  const { valid, segments } = useMemo(() => segmentPoints(points), [points])
  if (valid.length < 3) return null

  const values = valid.map((p) => p.value)
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
  // The time axis runs from the first to the last valid reading, so the final reading is exactly at the right edge.
  const t0 = valid[0].t
  const t1 = valid[valid.length - 1].t
  const x = (t: number) => (t1 === t0 ? 0 : ((t - t0) / (t1 - t0)) * W)
  const y = (v: number) => H - ((v - min) / span) * H
  const xy = (p: Valid) => `${x(p.t).toFixed(2)} ${y(p.value).toFixed(2)}`

  // Line and area are built from the same segments and the same coordinates. Each area closes straight down to the
  // baseline under its own first and last point, so it never extends past the line.
  const line = segments.map((s) => s.map((p, i) => `${i === 0 ? 'M' : 'L'}${xy(p)}`).join(' ')).join(' ')
  const area = segments
    .filter((s) => s.length >= 2)
    .map((s) => `M${x(s[0].t).toFixed(2)} ${H} ${s.map((p) => `L${xy(p)}`).join(' ')} L${x(s[s.length - 1].t).toFixed(2)} ${H} Z`)
    .join(' ')
  // A reading with no neighbour in its segment is shown as a dot, since a line needs two points.
  const singles = segments.filter((s) => s.length === 1).map((s) => s[0])

  const last = valid[valid.length - 1]
  const mid = (min + max) / 2
  const fmt = (v: number) => v.toFixed(decimals)
  const gaps = segments.length - 1
  const summary = `${label}, last ${valid.length} readings: lowest ${fmt(Math.min(...values))} ${unit}, highest ${fmt(Math.max(...values))} ${unit}, latest ${fmt(last.value)} ${unit}${gaps > 0 ? `, ${gaps} gap${gaps === 1 ? '' : 's'} without data` : ''}.`
  const gid = `fill-${uid}`
  const cid = `clip-${uid}`
  const pctX = (p: Valid) => `${(x(p.t) / W) * 100}%`
  const pctY = (p: Valid) => `${(y(p.value) / H) * 100}%`

  // Nearest valid reading to a horizontal position (0..1), by time.
  const nearest = (frac: number) => {
    const t = t0 + frac * (t1 - t0)
    let best = 0
    for (let i = 1; i < valid.length; i++) if (Math.abs(valid[i].t - t) < Math.abs(valid[best].t - t)) best = i
    return best
  }
  const h = hover === null ? null : valid[Math.min(hover, valid.length - 1)]

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
            setHover(nearest(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width))))
          }}
          onPointerLeave={() => setHover(null)}
          onBlur={() => setHover(null)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowLeft') setHover((v) => Math.max(0, (v ?? valid.length - 1) - 1))
            else if (e.key === 'ArrowRight') setHover((v) => Math.min(valid.length - 1, (v ?? valid.length - 1) + 1))
            else if (e.key === 'Escape') setHover(null)
          }}>
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={summary} className="h-full w-full overflow-visible">
            <defs>
              <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-indigo-400)" stopOpacity="0.22" />
                <stop offset="100%" stopColor="var(--color-indigo-400)" stopOpacity="0" />
              </linearGradient>
              {/*
                The draw-in animation reveals line and area together with a clip that grows from left to right. (A
                pathLength/dash animation cannot be used here: with a non-scaling stroke on a stretched SVG the dash is
                measured in screen pixels, so the line stopped short of its last point while the area went on.)
              */}
              <clipPath id={cid}>
                <m.rect x={-4} y={-10} height={H + 20}
                  initial={reduce ? false : { width: 0 }} animate={{ width: W + 8 }} transition={{ duration: 0.7, ease: [0.2, 0.7, 0.2, 1] }} />
              </clipPath>
            </defs>
            {[0, 50, 100].map((g) => (
              <line key={g} x1="0" x2={W} y1={g} y2={g} stroke="var(--color-slate-700)" strokeWidth="1" vectorEffect="non-scaling-stroke" strokeDasharray={g === 100 ? undefined : '3 4'} />
            ))}
            <g clipPath={`url(#${cid})`}>
              {area && <path d={area} fill={`url(#${gid})`} />}
              <path d={line} fill="none" stroke="var(--color-indigo-400)" strokeWidth={2} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
            </g>
          </svg>
          {singles.map((p) => (
            <span key={p.t} aria-hidden="true" className="absolute h-1.5 w-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-indigo-400" style={{ left: pctX(p), top: pctY(p) }} />
          ))}
          <span
            aria-hidden="true"
            className="absolute h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-800 bg-indigo-400"
            style={{ left: pctX(last), top: pctY(last) }}
          />
          {h && (
            <>
              <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 w-px bg-slate-500/60" style={{ left: pctX(h) }} />
              <span aria-hidden="true" className="pointer-events-none absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-800 bg-indigo-300"
                style={{ left: pctX(h), top: pctY(h) }} />
              <span role="status" className="pointer-events-none absolute -top-1 z-10 -translate-y-full whitespace-nowrap rounded-lg bg-slate-700 px-2.5 py-1.5 text-xs shadow-lg"
                style={{ left: pctX(h), transform: `translate(${x(h.t) > W * 0.7 ? '-100%' : x(h.t) < W * 0.3 ? '0' : '-50%'}, -100%)` }}>
                <span className="font-semibold text-slate-100">{fmt(h.value)} {unit}</span>
                <span className="ml-2 text-slate-400">{stamp(h.at)}</span>
              </span>
            </>
          )}
        </div>
      </div>
      <figcaption aria-hidden="true" className="relative mt-2 h-4 pl-10 text-xs tabular-nums text-slate-400">
        <span className="absolute left-10">{clock(valid[0].at)}</span>
        <span className="absolute -translate-x-1/2" style={{ left: 'calc(2.5rem + (100% - 2.5rem) / 2)' }}>{clock(new Date((t0 + t1) / 2).toISOString())}</span>
        <span className="absolute right-0">{clock(last.at)}</span>
      </figcaption>
    </figure>
  )
}
