import type { ReactNode } from 'react'
import { hhmm, pct, type Simulation } from '../predictive'

const W = 960
const H = 100
const ACCENT = '#713b50'
const MUTED = '#c3a7b2'
const ACTUAL = '#2f6f73'
const x = (minute: number) => (minute / 1440) * W

function Frame({ title, legend, label, children }: { title: string; legend: ReactNode; label: string; children: ReactNode }) {
  return (
    <figure className="card p-4">
      <figcaption className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-semibold text-slate-100">{title}</span>
        <span className="text-xs text-slate-400">{legend}</span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={label} className="mt-2 h-28 w-full border-b border-slate-600">
        {children}
      </svg>
      <div className="flex justify-between text-xs text-slate-400"><span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>24:00</span></div>
    </figure>
  )
}

export default function PredictiveCharts({ sim }: { sim: Simulation }) {
  const most = Math.max(1, ...sim.timeline.map((p) => p.motion))
  const line = (value: (p: Simulation['timeline'][number]) => number) =>
    sim.timeline.map((p) => `${x(p.minute)},${H - 4 - value(p) * (H - 8)}`).join(' ')
  const now = <line x1={x(sim.minute)} x2={x(sim.minute)} y1={0} y2={H} stroke="currentColor" strokeDasharray="3 3" className="text-slate-400" />
  return (
    <section className="space-y-3" aria-label="Activity charts">
      {([
        ['Weekday resident activity (synthetic history)', sim.activity.weekdayPresence, sim.activity.weekdayMotion],
        ['Weekend resident activity (synthetic history)', sim.activity.weekendPresence, sim.activity.weekendMotion],
      ] as const).map(([title, home, motion]) => (
        <Frame
          key={title}
          title={title}
          label={`${title}: share of each hour at home, and share of days with PIR motion`}
          legend={<><b style={{ color: MUTED }}>■ time at home</b>{' · '}<b style={{ color: ACCENT }}>━ days with PIR motion</b></>}
        >
          {home.map((r, h) => (
            <rect key={h} x={x(h * 60) + 2} width={x(60) - 4} y={H - r * H} height={r * H} fill={MUTED}><title>{`${hhmm(h * 60)}: home ${pct(r)}, motion ${pct(motion[h])}`}</title></rect>
          ))}
          <polyline points={motion.map((r, h) => `${x(h * 60 + 30)},${H - 4 - r * (H - 8)}`).join(' ')} fill="none" stroke={ACCENT} strokeWidth={2} vectorEffect="non-scaling-stroke" />
          {now}
        </Frame>
      ))}
      <Frame title="Actual PIR activity today (simulated)" legend={`Motion minutes per 15 min, tallest ${most}`} label="Simulated PIR motion today">
        {sim.timeline.map((p) => (
          <rect key={p.minute} x={x(p.minute) + 1} width={x(15) - 2} y={H - (p.motion / most) * H} height={(p.motion / most) * H} fill={ACCENT}><title>{`${hhmm(p.minute)}: ${p.motion}`}</title></rect>
        ))}
        {now}
      </Frame>
      <Frame
        title="Predicted versus actual occupancy"
        legend={<><b style={{ color: ACCENT }}>━ predicted probability</b>{' · '}<b style={{ color: ACTUAL }}>┅ actual (simulation ground truth)</b></>}
        label="Predicted occupancy probability against simulated actual occupancy"
      >
        <polyline points={line((p) => (p.actual ? 1 : 0))} fill="none" stroke={ACTUAL} strokeWidth={2} strokeDasharray="6 4" vectorEffect="non-scaling-stroke" />
        <polyline points={line((p) => p.predicted)} fill="none" stroke={ACCENT} strokeWidth={2} vectorEffect="non-scaling-stroke" />
        {now}
      </Frame>
    </section>
  )
}
