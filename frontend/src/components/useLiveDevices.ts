import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device } from '../api'

type Load = { devices: Device[]; fetchedAt: number } | null

const POLL_MS = 5000
// While a command is still on its way, check back every second so its progress shows up promptly.
const FAST_POLL_MS = 1000

/**
 * The owner's active devices, refreshed every few seconds, plus the seconds elapsed since the last fetch so that ages
 * ("12s ago") and online/stale decisions keep ticking between polls. A failed refresh keeps the last good data.
 */
export function useLiveDevices() {
  const [load, setLoad] = useState<Load>(null)
  const [error, setError] = useState('')
  const [now, setNow] = useState(() => Date.now())

  const reload = useCallback(() => {
    api
      .listDevices()
      .then((devices) => {
        setLoad({ devices: devices.filter((d) => d.status === 'ACTIVE'), fetchedAt: Date.now() })
        setError('')
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load devices'))
  }, [])

  const commandInFlight = !!load?.devices.some((d) => d.recentCommands.some((c) => c.status === 'PENDING' || c.status === 'SENT'))

  useEffect(() => {
    reload()
    const timer = setInterval(reload, commandInFlight ? FAST_POLL_MS : POLL_MS)
    return () => clearInterval(timer)
  }, [reload, commandInFlight])

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const elapsed = load ? Math.max(0, (now - load.fetchedAt) / 1000) : 0
  return { devices: load?.devices ?? null, error, elapsed, reload }
}
