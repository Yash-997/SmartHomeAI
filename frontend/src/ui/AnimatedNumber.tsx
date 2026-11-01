import { useEffect } from 'react'
import { animate, m, useMotionValue, useReducedMotion, useTransform } from 'motion/react'

/**
 * A number that glides to its new value instead of jumping. The text is updated by the animation itself (no React
 * re-render per frame). With "reduce motion" on, it changes instantly.
 */
export default function AnimatedNumber({ value, decimals = 1, suffix = '' }: { value: number; decimals?: number; suffix?: string }) {
  const reduce = useReducedMotion()
  const motionValue = useMotionValue(value)
  const text = useTransform(motionValue, (v) => `${v.toFixed(decimals)}${suffix}`)

  useEffect(() => {
    if (reduce) {
      motionValue.set(value)
      return
    }
    const controls = animate(motionValue, value, { duration: 0.35, ease: [0.2, 0.7, 0.2, 1] })
    return () => controls.stop()
  }, [value, reduce, motionValue])

  return <m.span>{text}</m.span>
}
