import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api'

export interface Polled<T> {
  /** null until the first answer (or failure) has arrived. */
  data: T | null
  error: string
  reload: () => void
  /** Replace the data after an action that returned fresh data. */
  set: (next: T) => void
}

/**
 * Loads once and then every {@code everyMs}. A later failure keeps the last good data and only sets the error, so a brief
 * network problem does not blank the panel.
 */
export function usePolled<T>(load: () => Promise<T>, everyMs = 30000): Polled<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')

  const reload = useCallback(() => {
    load()
      .then((d) => {
        setData(d)
        setError('')
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not load'))
  }, [load])

  useEffect(() => {
    reload()
    const timer = setInterval(reload, everyMs)
    return () => clearInterval(timer)
  }, [reload, everyMs])

  return { data, error, reload, set: setData }
}
