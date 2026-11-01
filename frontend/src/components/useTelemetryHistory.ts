import { useEffect, useState } from 'react'
import { api, type Telemetry } from '../api'

/**
 * The latest stored readings of a device, oldest first, refreshed every 30 s. The result is tagged with the device it
 * belongs to, so switching device never shows the previous device's line.
 */
export function useTelemetryHistory(deviceId: string | null, limit = 60): Telemetry[] {
  const [loaded, setLoaded] = useState<{ id: string; rows: Telemetry[] } | null>(null)

  useEffect(() => {
    if (!deviceId) return
    let cancelled = false
    const load = () =>
      api
        .telemetryHistory(deviceId, limit)
        .then((rows) => !cancelled && setLoaded({ id: deviceId, rows: [...rows].reverse() }))
        .catch(() => undefined)       // a trend line is optional: if it cannot load, the page simply shows none
    load()
    const timer = setInterval(load, 30000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [deviceId, limit])

  return loaded && loaded.id === deviceId ? loaded.rows : []
}
