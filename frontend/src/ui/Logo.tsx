import { useId } from 'react'
import { m, useReducedMotion } from 'motion/react'

/*
 * The VÉYRA mark: an architectural house outline whose corners are network nodes, a diamond "intelligence" node at the
 * apex and a wireless signal inside. The wordmark is drawn from strokes (no font), so it looks the same everywhere and
 * matches the PDF reports and the SVG files in /public/brand.
 */
const HOUSE = 'M16 50V29L32 15L48 29V50'
const ARCS = 'M27.05 39.05A7 7 0 0 1 36.95 39.05M22.81 34.81A13 13 0 0 1 41.19 34.81'
const DIAMOND = 'M32 9.5L37.5 15L32 20.5L26.5 15Z'
const LETTERS = [
  'M0 0L9 24L18 0',
  'M39 0H26V24H39M26 12H36M30.5 -3.5L35 -8',
  'M46 0L55 12.5L64 0M55 12.5V24',
  'M72 24V0H79.5C82.95 0 85.75 2.8 85.75 6.25C85.75 9.7 82.95 12.5 79.5 12.5H72M78.5 12.5L87 24',
  'M94 24L103 0L112 24M97.4 15H108.6',
]

type MarkProps = {
  size?: number
  /** Draw the burgundy rounded tile behind the mark. */
  tile?: boolean
  /** Play the one-time drawing animation. */
  intro?: boolean
  color?: string
  className?: string
}

export function LogoMark({ size = 32, tile = true, intro = false, color, className }: MarkProps) {
  // White on the burgundy tile; burgundy when drawn straight onto a light surface.
  const ink = color ?? (tile ? '#fff' : '#713B50')
  const id = useId().replace(/:/g, '')
  const reduce = useReducedMotion()
  const play = intro && !reduce
  const draw = (delay: number) =>
    play
      ? { initial: { pathLength: 0, opacity: 0 }, animate: { pathLength: 1, opacity: 1 }, transition: { duration: 0.9, delay, ease: [0.65, 0, 0.35, 1] as const } }
      : {}
  const pop = (delay: number) =>
    play ? { initial: { scale: 0, opacity: 0 }, animate: { scale: 1, opacity: 1 }, transition: { duration: 0.35, delay, ease: [0.2, 0.7, 0.2, 1] as const } } : {}

  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-hidden="true" focusable="false" className={className}>
      {tile && (
        <>
          <defs>
            <linearGradient id={`vg${id}`} x1="0" y1="0" x2="64" y2="64" gradientUnits="userSpaceOnUse">
              <stop offset="0" stopColor="#8B5369" />
              <stop offset="1" stopColor="#5E2F42" />
            </linearGradient>
          </defs>
          <rect width="64" height="64" rx="15" fill={`url(#vg${id})`} />
          <rect x="0.75" y="0.75" width="62.5" height="62.5" rx="14.25" fill="none" stroke="#fff" strokeOpacity=".14" strokeWidth="1.5" />
        </>
      )}
      <g fill="none" stroke={ink} strokeLinecap="round" strokeLinejoin="round">
        <m.path d={HOUSE} strokeWidth="4" {...draw(0.05)} />
        <m.path d={ARCS} strokeWidth="3" {...draw(0.55)} />
      </g>
      <g fill={ink}>
        <m.circle cx="32" cy="44" r="2.6" style={{ originX: '32px', originY: '44px' }} {...pop(0.5)} />
        <m.circle cx="16" cy="29" r="3.4" style={{ originX: '16px', originY: '29px' }} {...pop(0.3)} />
        <m.circle cx="48" cy="29" r="3.4" style={{ originX: '48px', originY: '29px' }} {...pop(0.3)} />
        <m.path d={DIAMOND} style={{ originX: '32px', originY: '15px' }} {...pop(0.75)} />
      </g>
    </svg>
  )
}

export function Wordmark({ height = 16, color = '#713B50', intro = false, className }: { height?: number; color?: string; intro?: boolean; className?: string }) {
  const reduce = useReducedMotion()
  const play = intro && !reduce
  // viewBox covers the accent above the É (y from -9) to the baseline (24).
  return (
    <svg viewBox="-2 -10 116 36" height={height} width={(height * 116) / 36} aria-hidden="true" focusable="false" className={className}>
      <g fill="none" stroke={color} strokeWidth="2.6" strokeLinejoin="miter">
        {LETTERS.map((d, i) => (
          <m.path
            key={d}
            d={d}
            {...(play
              ? { initial: { opacity: 0, y: 6 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.45, delay: 0.5 + i * 0.07, ease: [0.2, 0.7, 0.2, 1] as const } }
              : {})}
          />
        ))}
      </g>
    </svg>
  )
}

/** The horizontal lock-up: tile mark + wordmark. The accessible name is the product name. */
export default function Logo({ size = 34, intro = false, tagline = false }: { size?: number; intro?: boolean; tagline?: boolean }) {
  return (
    <span className="inline-flex items-center gap-3" role="img" aria-label="VÉYRA">
      <LogoMark size={size} intro={intro} />
      <span className="flex flex-col gap-1">
        <Wordmark height={size * 0.5} intro={intro} />
        {tagline && <span className="text-[10px] font-semibold uppercase tracking-[0.2em] text-slate-400">Your Home. Your Intelligence.</span>}
      </span>
    </span>
  )
}
