import { useId } from 'react'
import { m, useReducedMotion } from 'motion/react'

interface Props {
  /** Oldest first. null = that reading had no value: the line leaves a gap there. */
  values: (number | null)[]
  /** What the line shows, for the accessible description, e.g. "Temperature". */
  label: string
  unit: string
  decimals?: number
  height?: number
}

/** A small trend line that draws itself once, with the lowest and highest values labelled. */
export default function Sparkline({ values, label, unit, decimals = 1, height = 56 }: Props) {
  const uid = useId().replace(/:/g, '')
  const reduce = useReducedMotion()
  // Trim readings without a value at both ends, so the last plotted point is the last real reading.
  const first = values.findIndex((v) => v !== null)
  const end = values.length - [...values].reverse().findIndex((v) => v !== null)
  const series = first < 0 ? [] : values.slice(first, end)
  const real = series.filter((v): v is number => v !== null)
  if (real.length < 3) return null
  const width = 240
  const pad = 4
  const min = Math.min(...real)
  const max = Math.max(...real)
  const span = max - min || 1
  const x = (i: number) => pad + (i / (series.length - 1)) * (width - pad * 2)
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2)
  // Runs of consecutive real readings; a null ends a run. Line and area use the same runs.
  const runs: { i: number; v: number }[][] = [[]]
  series.forEach((v, i) => (v === null ? runs.push([]) : runs[runs.length - 1].push({ i, v })))
  const segs = runs.filter((r) => r.length > 0)
  const line = segs.map((r) => r.map((p, k) => `${k === 0 ? 'M' : 'L'}${x(p.i).toFixed(1)} ${y(p.v).toFixed(1)}`).join(' ')).join(' ')
  const area = segs.filter((r) => r.length > 1)
    .map((r) => `M${x(r[0].i).toFixed(1)} ${height} ${r.map((p) => `L${x(p.i).toFixed(1)} ${y(p.v).toFixed(1)}`).join(' ')} L${x(r[r.length - 1].i).toFixed(1)} ${height} Z`).join(' ')
  const last = real[real.length - 1]
  const lastX = x(series.length - 1)
  const summary = `${label}, last ${real.length} readings: lowest ${min.toFixed(decimals)} ${unit}, highest ${max.toFixed(decimals)} ${unit}, latest ${last.toFixed(decimals)} ${unit}.`

  return (
    <figure className="m-0">
      <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label={summary} className="h-14 w-full overflow-visible">
        {/* Revealed by a growing clip: a pathLength (dash) animation with a non-scaling stroke on a stretched SVG stops short of the end. */}
        <defs>
          <clipPath id={`spark-${uid}`}>
            <m.rect x={-4} y={-4} height={height + 8} initial={reduce ? false : { width: 0 }} animate={{ width: width + 8 }} transition={{ duration: 0.7, ease: [0.2, 0.7, 0.2, 1] }} />
          </clipPath>
        </defs>
        <g clipPath={`url(#spark-${uid})`}>
          <path d={area} fill="var(--color-indigo-400)" fillOpacity={0.08} />
          <path d={line} fill="none" stroke="var(--color-indigo-400)" strokeWidth={1.6} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
        </g>
        {/* The latest point: a zero-length round-capped line stays a true circle even though the chart stretches to its box. */}
        <m.line x1={lastX} y1={y(last)} x2={lastX} y2={y(last) + 0.001} stroke="var(--color-indigo-400)" strokeWidth={6} strokeLinecap="round" vectorEffect="non-scaling-stroke" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.6, duration: 0.2 }} />
      </svg>
      <figcaption className="mt-1 flex justify-between text-[11px] text-slate-500">
        <span>low {min.toFixed(decimals)} {unit}</span>
        <span>high {max.toFixed(decimals)} {unit}</span>
      </figcaption>
    </figure>
  )
}
