import type { OccupancyPattern } from '../api'
import { DAYS, hourLabel, patternGrid, patternSummary, pct } from '../insights'

/** The learned week: 7 rows x 24 hours, shaded by the model's chance of motion. Hatched cells had no training data. */
export default function PatternHeatmap({ pattern }: { pattern: OccupancyPattern }) {
  const grid = patternGrid(pattern)
  const s = patternSummary(pattern)
  const hours = (hs: number[]) => (hs.length ? hs.map(hourLabel).join(', ') : 'not enough data')

  return (
    <div>
      <div className="overflow-x-auto">
        <table className="border-separate border-spacing-0.5 text-[10px]" aria-label="Learned weekly motion pattern">
          <thead>
            <tr>
              <th className="w-8" />
              {Array.from({ length: 24 }, (_, h) => (
                <th key={h} scope="col" className="w-5 font-normal text-slate-500">{h % 3 === 0 ? String(h).padStart(2, '0') : ''}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {grid.map((row, d) => (
              <tr key={DAYS[d]}>
                <th scope="row" className="pr-1 text-left font-normal text-slate-400">{DAYS[d]}</th>
                {row.map((c, h) => {
                  const empty = !c || c.slots === 0
                  const p = c?.modelProbability ?? 0
                  return (
                    <td key={h}
                      title={c ? `${DAYS[d]} ${hourLabel(h)} · model ${pct(p)} · seen in ${c.observedRate === null ? 'no' : pct(c.observedRate)} of ${c.slots} past slots` : ''}
                      className={`h-4 w-5 rounded-sm ${empty ? 'bg-[repeating-linear-gradient(45deg,#334155,#334155_2px,#1e293b_2px,#1e293b_4px)]' : ''}`}
                      style={empty ? undefined : { backgroundColor: `rgba(129, 140, 248, ${0.08 + 0.92 * p})` }} />
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-1 text-[11px] text-slate-500">Darker = more likely to see motion ({pattern.timeZone}). Hatched = no training data for that hour yet.</p>
      <dl className="mt-2 grid gap-1 text-xs sm:grid-cols-2">
        <div><dt className="inline text-slate-400">Weekdays busiest: </dt><dd className="inline">{hours(s.weekdayBusy)}</dd></div>
        <div><dt className="inline text-slate-400">Weekdays quietest: </dt><dd className="inline">{hours(s.weekdayQuiet)}</dd></div>
        <div><dt className="inline text-slate-400">Weekends busiest: </dt><dd className="inline">{hours(s.weekendBusy)}</dd></div>
        <div><dt className="inline text-slate-400">Weekends quietest: </dt><dd className="inline">{hours(s.weekendQuiet)}</dd></div>
      </dl>
    </div>
  )
}
