import { useState } from 'react'
import type { Visual } from '../api'

const W = 320
const H = 120

function label(raw: string, all: string[]): string {
  const t = Date.parse(raw)
  if (Number.isNaN(t) || !/^\d{4}-\d{2}-\d{2}T/.test(raw)) return raw
  const d = new Date(t)
  const spanDays = all.length > 1 ? (Date.parse(all[all.length - 1]) - Date.parse(all[0])) / 86400000 : 0
  return spanDays > 2
    ? d.toLocaleDateString([], { day: 'numeric', month: 'short' })
    : d.toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

const fmt = (v: number | null | undefined) => (v === null || v === undefined ? '–' : Number.isInteger(v) ? String(v) : v.toFixed(1))

function TableView({ v }: { v: Visual }) {
  const columns = v.kind === 'table' ? v.columns : ['', ...v.series.map((s) => s.name)]
  const rows = v.kind === 'table' ? v.rows : v.labels.map((l, i) => [label(l, v.labels), ...v.series.map((s) => fmt(s.values[i]))])
  return (
    <div className="max-h-72 overflow-auto rounded-lg border border-slate-600/60">
      <table className="w-full text-left text-xs">
        <thead className="sticky top-0 bg-slate-800 text-slate-300">
          <tr>{columns.map((c, i) => <th key={i} scope="col" className="whitespace-nowrap px-2.5 py-1.5 font-medium">{c}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-slate-700/60">
              {r.map((c, j) => <td key={j} className="whitespace-nowrap px-2.5 py-1 tabular-nums text-slate-200">{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Average as the line; min-max (when present) as a quiet band, so one colour carries the meaning. */
function LineChart({ v }: { v: Visual }) {
  const [hover, setHover] = useState<number | null>(null)
  const avg = v.series.find((s) => s.name === 'average') ?? v.series[0]
  const lo = v.series.find((s) => s.name === 'min')
  const hi = v.series.find((s) => s.name === 'max')
  const all = [...avg.values, ...(lo?.values ?? []), ...(hi?.values ?? [])].filter((x): x is number => x !== null)
  if (all.length === 0) return <p className="text-xs text-slate-400">No values in this period.</p>
  let min = Math.min(...all)
  let max = Math.max(...all)
  if (max - min < 0.5) {
    min -= 0.5
    max += 0.5
  }
  const n = v.labels.length
  const x = (i: number) => (n === 1 ? W / 2 : (i / (n - 1)) * W)
  const y = (val: number) => H - ((val - min) / (max - min)) * H
  // Break the path at missing values: a gap in the data is a gap in the line.
  let d = ''
  let pen = false
  avg.values.forEach((val, i) => {
    if (val === null) {
      pen = false
      return
    }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)} ${y(val).toFixed(1)} `
    pen = true
  })
  let band = ''
  if (lo && hi) {
    const idx = lo.values.map((val, i) => (val !== null && hi.values[i] !== null ? i : -1)).filter((i) => i >= 0)
    if (idx.length >= 2) {
      band = idx.map((i, k) => `${k ? 'L' : 'M'}${x(i).toFixed(1)} ${y(hi.values[i] as number).toFixed(1)}`).join(' ')
      band += ' ' + [...idx].reverse().map((i) => `L${x(i).toFixed(1)} ${y(lo.values[i] as number).toFixed(1)}`).join(' ') + ' Z'
    }
  }
  const valid = avg.values.filter((x): x is number => x !== null)
  const summary = `${v.title}: ${n} points from ${label(v.labels[0], v.labels)} to ${label(v.labels[n - 1], v.labels)}, average between ${fmt(Math.min(...valid))} and ${fmt(Math.max(...valid))} ${v.unit}.`
  return (
    <div className="flex gap-2">
      <div aria-hidden="true" className="flex flex-col justify-between py-0.5 text-right text-[11px] tabular-nums text-slate-400" style={{ height: H }}>
        <span>{fmt(max)}</span><span>{fmt((min + max) / 2)}</span><span>{fmt(min)}</span>
      </div>
      <div className="min-w-0 flex-1">
        <div className="relative" style={{ height: H }}
          onPointerMove={(e) => {
            const r = e.currentTarget.getBoundingClientRect()
            setHover(Math.max(0, Math.min(n - 1, Math.round(((e.clientX - r.left) / r.width) * (n - 1)))))
          }}
          onPointerLeave={() => setHover(null)}>
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={summary} className="h-full w-full overflow-visible">
            {[0, H / 2, H].map((g) => (
              <line key={g} x1="0" x2={W} y1={g} y2={g} stroke="var(--color-slate-700)" strokeWidth="1" vectorEffect="non-scaling-stroke" strokeDasharray={g === H ? undefined : '3 4'} />
            ))}
            {band && <path d={band} fill="var(--color-indigo-400)" fillOpacity="0.14" />}
            <path d={d} fill="none" stroke="var(--color-indigo-400)" strokeWidth={2} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
            {n === 1 && avg.values[0] !== null && <circle cx={x(0)} cy={y(avg.values[0])} r="4" fill="var(--color-indigo-400)" />}
          </svg>
          {hover !== null && (
            <>
              <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 w-px bg-slate-500/60" style={{ left: `${(x(hover) / W) * 100}%` }} />
              <span role="status" className="pointer-events-none absolute -top-1 z-10 -translate-y-full whitespace-nowrap rounded-lg bg-slate-900 px-2.5 py-1.5 text-xs shadow-lg ring-1 ring-slate-700"
                style={{ left: `${(x(hover) / W) * 100}%`, transform: `translate(${hover > n * 0.6 ? '-100%' : hover < n * 0.3 ? '0' : '-50%'}, -100%)` }}>
                <span className="text-slate-400">{label(v.labels[hover], v.labels)}</span>{' '}
                <span className="font-semibold text-slate-100">{fmt(avg.values[hover])} {v.unit}</span>
                {lo && hi && lo.values[hover] !== null && <span className="ml-1 text-slate-400">(min {fmt(lo.values[hover])}, max {fmt(hi.values[hover])})</span>}
              </span>
            </>
          )}
        </div>
        <div aria-hidden="true" className="mt-1 flex justify-between text-[11px] tabular-nums text-slate-400">
          <span>{label(v.labels[0], v.labels)}</span>
          {n > 2 && <span>{label(v.labels[Math.floor((n - 1) / 2)], v.labels)}</span>}
          {n > 1 && <span>{label(v.labels[n - 1], v.labels)}</span>}
        </div>
        {band && <p className="mt-1 text-[11px] text-slate-400">Line: average · shaded band: lowest to highest value</p>}
      </div>
    </div>
  )
}

function BarChart({ v }: { v: Visual }) {
  const [hover, setHover] = useState<number | null>(null)
  const s = v.series[0]
  const vals = s.values.map((x) => x ?? 0)
  const max = Math.max(1, ...vals)
  const n = v.labels.length
  const slot = W / n
  const bw = Math.max(2, slot - 2)                     // 2px gap between neighbours
  return (
    <div>
      <div className="relative" style={{ height: H }} onPointerLeave={() => setHover(null)}>
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" className="h-full w-full"
          aria-label={`${v.title}: ${v.labels.map((l, i) => `${l} ${fmt(s.values[i])}`).join(', ')}`}>
          <line x1="0" x2={W} y1={H} y2={H} stroke="var(--color-slate-600)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          {vals.map((val, i) => {
            const h = (val / max) * (H - 4)
            return (
              <g key={i} onPointerEnter={() => setHover(i)}>
                <rect x={i * slot} y={0} width={slot} height={H} fill="transparent" />
                {s.values[i] !== null && val > 0 && (
                  <rect x={i * slot + (slot - bw) / 2} y={H - h} width={bw} height={h} rx={Math.min(4, bw / 2)}
                    fill="var(--color-indigo-400)" fillOpacity={hover === null || hover === i ? 1 : 0.55} />
                )}
              </g>
            )
          })}
        </svg>
        {hover !== null && (
          <span role="status" className="pointer-events-none absolute -top-1 z-10 -translate-y-full whitespace-nowrap rounded-lg bg-slate-900 px-2.5 py-1.5 text-xs shadow-lg ring-1 ring-slate-700"
            style={{ left: `${((hover + 0.5) / n) * 100}%`, transform: `translate(${hover > n * 0.6 ? '-100%' : hover < n * 0.3 ? '0' : '-50%'}, -100%)` }}>
            <span className="text-slate-400">{v.labels[hover]}</span> <span className="font-semibold text-slate-100">{fmt(s.values[hover])} {v.unit}</span>
          </span>
        )}
      </div>
      <div aria-hidden="true" className="mt-1 flex justify-between text-[11px] tabular-nums text-slate-400">
        <span>{v.labels[0]}</span>{n > 2 && <span>{v.labels[Math.floor((n - 1) / 2)]}</span>}{n > 1 && <span>{v.labels[n - 1]}</span>}
      </div>
    </div>
  )
}

/** One chart or table from the assistant or a report. Every chart can be switched to a table of its numbers. */
export default function VisualView({ visual }: { visual: Visual }) {
  const [asTable, setAsTable] = useState(false)
  const chart = visual.kind !== 'table' && visual.labels.length > 0 && visual.series.length > 0
  return (
    <figure className="m-0 rounded-xl bg-slate-900/60 p-3">
      <figcaption className="mb-2 flex items-center justify-between gap-2 text-xs font-medium text-slate-300">
        <span>{visual.title}{chart && visual.unit ? ` (${visual.unit})` : ''}</span>
        {chart && (
          <button type="button" onClick={() => setAsTable((t) => !t)} className="rounded px-1.5 py-0.5 text-[11px] text-indigo-300 hover:bg-slate-700">
            {asTable ? 'Show chart' : 'Show table'}
          </button>
        )}
      </figcaption>
      {!chart || asTable ? <TableView v={visual} /> : visual.kind === 'bar' ? <BarChart v={visual} /> : <LineChart v={visual} />}
    </figure>
  )
}
