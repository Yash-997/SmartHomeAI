import type { ReactNode } from 'react'
import { AnimatePresence, LazyMotion, MotionConfig, domAnimation, m } from 'motion/react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth'
import Assistant from './pages/Assistant'
import Dashboard from './pages/Dashboard'
import Automation from './pages/Automation'
import Devices from './pages/Devices'
import Insights from './pages/Insights'
import Login from './pages/Login'
import Register from './pages/Register'
import Backdrop from './ui/Backdrop'
import ToastHost from './ui/ToastHost'

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) {
    return <main className="flex min-h-screen items-center justify-center bg-slate-900 text-slate-400" role="status">Loading...</main>
  }
  return user ? children : <Navigate to="/login" replace />
}

/** Pages cross-fade: the old page leaves quickly, then the new one arrives with a small upward settle. */
function AnimatedRoutes() {
  const location = useLocation()
  return (
    <AnimatePresence mode="wait" initial={false}>
      <m.div
        key={location.pathname}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0, transition: { duration: 0.22, ease: [0.2, 0.7, 0.2, 1] } }}
        exit={{ opacity: 0, transition: { duration: 0.12 } }}
      >
        <Routes location={location}>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/" element={<RequireAuth><Dashboard /></RequireAuth>} />
          <Route path="/devices" element={<RequireAuth><Devices /></RequireAuth>} />
          <Route path="/automation" element={<RequireAuth><Automation /></RequireAuth>} />
          <Route path="/insights" element={<RequireAuth><Insights /></RequireAuth>} />
          <Route path="/assistant" element={<RequireAuth><Assistant /></RequireAuth>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </m.div>
    </AnimatePresence>
  )
}

function App() {
  return (
    // reducedMotion="user": with "reduce motion" on, movement and scaling are switched off (fades remain).
    <LazyMotion features={domAnimation} strict>
    <MotionConfig reducedMotion="user">
      <BrowserRouter>
        <AuthProvider>
          <Backdrop />
          <AnimatedRoutes />
          <ToastHost />
        </AuthProvider>
      </BrowserRouter>
    </MotionConfig>
    </LazyMotion>
  )
}

export default App
