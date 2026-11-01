import { m } from 'motion/react'

interface Props {
  /** Oldest first. */
  values: number[]
  /** What the line shows, for the accessible description, e.g. "Temperature". */
  label: string
  unit: string
  decimals?: number
  height?: number
}

/** A small trend line that draws itself once, with the lowest and highest values labelled. */
export default function Sparkline({ values, label, unit, decimals = 1, height = 56 }: Props) {
  if (values.length < 3) return null
  const width = 240
  const pad = 4
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const x = (i: number) => pad + (i / (values.length - 1)) * (width - pad * 2)
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2)
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ')
  const area = `${line} L${x(values.length - 1).toFixed(1)} ${height} L${x(0).toFixed(1)} ${height} Z`
  const last = values[values.length - 1]
  const summary = `${label}, last ${values.length} readings: lowest ${min.toFixed(decimals)} ${unit}, highest ${max.toFixed(decimals)} ${unit}, latest ${last.toFixed(decimals)} ${unit}.`

  return (
    <figure className="m-0">
      <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label={summary} className="h-14 w-full overflow-visible">
        <m.path d={area} fill="var(--color-indigo-400)" initial={{ opacity: 0 }} animate={{ opacity: 0.08 }} transition={{ duration: 0.4, delay: 0.25 }} />
        <m.path
          d={line}
          fill="none"
          stroke="var(--color-indigo-400)"
          strokeWidth={1.6}
          vectorEffect="non-scaling-stroke"
          strokeLinecap="round"
          strokeLinejoin="round"
          initial={{ pathLength: 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: 0.7, ease: [0.2, 0.7, 0.2, 1] }}
        />
        {/* The latest point: a zero-length round-capped line stays a true circle even though the chart stretches to its box. */}
        <m.line x1={x(values.length - 1)} y1={y(last)} x2={x(values.length - 1)} y2={y(last) + 0.001} stroke="var(--color-indigo-400)" strokeWidth={6} strokeLinecap="round" vectorEffect="non-scaling-stroke" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.6, duration: 0.2 }} />
      </svg>
      <figcaption className="mt-1 flex justify-between text-[11px] text-slate-500">
        <span>low {min.toFixed(decimals)} {unit}</span>
        <span>high {max.toFixed(decimals)} {unit}</span>
      </figcaption>
    </figure>
  )
}
