// Decorative activity indicator: animated bars while listening or speaking. It does not analyse the microphone
// (that would need a second audio capture), so it shows *that* something is happening, not how loud it is.
const BARS = [0.45, 0.8, 0.6, 1, 0.7, 0.9, 0.5, 0.75, 0.55]

export default function Waveform({ active, tone }: { active: boolean; tone: 'listen' | 'speak' }) {
  const color = tone === 'listen' ? 'bg-indigo-400' : 'bg-sky-300'
  return (
    <div className="flex h-10 items-center justify-center gap-1" aria-hidden="true" data-testid="waveform" data-active={active}>
      {BARS.map((h, i) => (
        <span
          key={i}
          className={`wave-bar w-1.5 rounded-full ${active ? color : 'bg-slate-600'}`}
          style={{ height: `${Math.round(h * 100)}%`, animationDelay: `${i * 90}ms`, animationPlayState: active ? 'running' : 'paused' }}
        />
      ))}
    </div>
  )
}
