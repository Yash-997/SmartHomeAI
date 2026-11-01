/** A quiet placeholder block shown while real content loads (the shimmer is in index.css and stops under reduced motion). */
export function Skeleton({ className = '' }: { className?: string }) {
  return <div aria-hidden="true" className={`skeleton rounded-md ${className}`} />
}

/** Placeholder lines for a panel that is still loading. Announced once to screen readers as "Loading". */
export function PanelSkeleton({ lines = 3 }: { lines?: number }) {
  const widths = ['w-full', 'w-5/6', 'w-2/3', 'w-3/4']
  return (
    <div role="status" className="mt-3 space-y-2.5">
      <span className="sr-only">Loading</span>
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} className={`h-4 ${widths[i % widths.length]}`} />
      ))}
    </div>
  )
}
