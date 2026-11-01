/**
 * A very faint, slow-moving wash of teal behind the pages. Two soft radial shapes drift over about a minute; they are
 * decoration only (hidden from assistive technology, never intercept clicks) and stand still under reduced motion.
 */
export default function Backdrop() {
  return (
    <div aria-hidden="true" className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div className="backdrop-shape backdrop-shape-a" />
      <div className="backdrop-shape backdrop-shape-b" />
    </div>
  )
}
