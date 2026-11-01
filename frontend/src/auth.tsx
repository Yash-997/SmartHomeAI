import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, setUnauthorizedListener, tokenStore, type AuthResponse, type UserProfile } from './api'

interface AuthState {
  user: UserProfile | null
  /** True while a stored token is being verified on first load. */
  loading: boolean
  /** Set when the session ended because the token was invalid or expired. */
  sessionExpired: boolean
  login: (username: string, password: string) => Promise<void>
  register: (username: string, email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState<boolean>(() => tokenStore.get() !== null)
  const [sessionExpired, setSessionExpired] = useState(false)

  const logout = useCallback(() => {
    tokenStore.clear()
    setUser(null)
  }, [])

  // Any 401 on a protected request ends the session.
  useEffect(() => {
    setUnauthorizedListener(() => {
      tokenStore.clear()
      setUser(null)
      setSessionExpired(true)
    })
  }, [])

  // Restore the session from a stored token.
  useEffect(() => {
    if (!tokenStore.get()) return
    api
      .me()
      .then(setUser)
      .catch(() => {
        // 401 is handled by the listener; other errors (e.g. server down) just leave the user signed out.
      })
      .finally(() => setLoading(false))
  }, [])

  const accept = (res: AuthResponse) => {
    tokenStore.set(res.token)
    setSessionExpired(false)
    setUser(res.user)
  }

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      sessionExpired,
      login: async (username, password) => accept(await api.login(username, password)),
      register: async (username, email, password) => accept(await api.register(username, email, password)),
      logout,
    }),
    [user, loading, sessionExpired, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
