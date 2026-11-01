import type { Connection, Device } from './api'

/** "5s", "3 min", "2 h", "4 d" */
export function formatAge(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)} min`
  if (s < 86400) return `${Math.floor(s / 3600)} h`
  return `${Math.floor(s / 86400)} d`
}

/**
 * Ages are the server-measured age at fetch time plus the time elapsed since the fetch. That keeps the display
 * ticking between polls without depending on the browser clock matching the server clock.
 */
export function liveStatus(device: Device, elapsedSeconds: number) {
  const lastSeenAge = device.lastSeenAgeSeconds === null ? null : device.lastSeenAgeSeconds + elapsedSeconds
  const readingAge = device.latestTelemetry ? device.latestTelemetry.ageSeconds + elapsedSeconds : null

  let connection: Connection = device.connection
  // The server said ONLINE at fetch time; if we've since gone silent for too long, don't keep claiming it.
  if (connection === 'ONLINE' && lastSeenAge !== null && lastSeenAge > device.staleAfterSeconds) connection = 'OFFLINE'

  const stale = readingAge !== null && readingAge > device.staleAfterSeconds
  return { connection, lastSeenAge, readingAge, stale }
}
