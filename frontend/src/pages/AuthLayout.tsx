import type { ReactNode } from 'react'
import { AnimatePresence, m } from 'motion/react'
import { Outlet, useLocation } from 'react-router-dom'
import Logo, { LogoMark, Wordmark } from '../ui/Logo'

const YEAR = new Date().getFullYear()

const FEATURES: { title: string; text: string; icon: ReactNode }[] = [
  {
    title: 'Live sensor monitoring',
    text: 'Temperature, humidity, motion and gas from your ESP32 devices over MQTT.',
    icon: <path d="M3 12h4l3-7 4 14 3-7h4" />,
  },
  {
    title: 'Automation you can audit',
    text: 'Telemetry and schedule rules, each run recorded with its outcome.',
    icon: <path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z" />,
  },
  {
    title: 'Predictive insights',
    text: 'Predictive occupancy on synthetic data, and anomaly detection.',
    icon: <path d="M3 20h18M6 16l4-5 3 3 5-7" />,
  },
  {
    title: 'An assistant grounded in your data',
    text: 'Answers cite their sources; device commands always ask first.',
    icon: <path d="M4 5h16v11H8l-4 4V5Z" />,
  },
]

/**
 * The frame of the sign-in and registration screens. It stays mounted while the visitor switches between the two,
 * so only the form slides; the brand panel and the logo animation play once.
 */
export default function AuthLayout() {
  const location = useLocation()
  return (
    <main className="relative grid min-h-screen overflow-hidden text-slate-100 lg:grid-cols-[1.05fr_1fr]">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="aurora aurora-a" />
        <div className="aurora aurora-b" />
        <div className="grid-lines absolute inset-0" />
      </div>

      {/* Brand panel (desktop) */}
      <m.section
        aria-label="About VÉYRA"
        className="relative hidden flex-col justify-between border-r border-slate-800 bg-slate-900/40 p-12 lg:flex xl:p-16"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.6 }}
      >
        <Logo size={44} intro />
        <div className="max-w-lg">
          <m.p className="brand-eyebrow" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.5, duration: 0.5 }}>
            Intelligent home automation
          </m.p>
          <m.h2
            className="font-display mt-4 text-5xl font-semibold leading-[1.05] xl:text-6xl"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.6, duration: 0.6, ease: [0.2, 0.7, 0.2, 1] }}
          >
            Your Home.
            <br />
            <span className="bg-linear-to-r from-indigo-400 via-indigo-300 to-indigo-700 bg-clip-text text-transparent">Your Intelligence.</span>
          </m.h2>
          <m.p className="mt-5 text-[15px] leading-relaxed text-slate-400" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.8, duration: 0.6 }}>
            VÉYRA brings IoT connectivity, real-time monitoring, predictive analytics, anomaly detection and AI assistance
            together in one place.
          </m.p>
          <ul className="mt-9 grid gap-4 sm:grid-cols-2">
            {FEATURES.map((f, i) => (
              <m.li
                key={f.title}
                className="flex gap-3"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.95 + i * 0.08, duration: 0.45, ease: [0.2, 0.7, 0.2, 1] }}
              >
                <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-slate-700 bg-slate-800 text-indigo-300">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">{f.icon}</svg>
                </span>
                <span>
                  <span className="block text-sm font-semibold">{f.title}</span>
                  <span className="mt-0.5 block text-xs leading-relaxed text-slate-400">{f.text}</span>
                </span>
              </m.li>
            ))}
          </ul>
        </div>
        <p className="text-xs text-slate-500">© {YEAR} VÉYRA · Your Home. Your Intelligence.</p>
      </m.section>

      {/* Form column */}
      <div className="flex min-h-screen flex-col items-center justify-center px-4 py-10 sm:px-8">
        <div className="mb-8 flex flex-col items-center gap-4 lg:hidden" role="img" aria-label="VÉYRA">
          <LogoMark size={60} intro />
          <Wordmark height={22} intro />
          <p className="text-[10px] font-semibold uppercase tracking-[0.22em] text-slate-400">Your Home. Your Intelligence.</p>
        </div>
        <div className="w-full max-w-[420px]">
          <AnimatePresence mode="wait" initial={false}>
            <m.div
              key={location.pathname}
              initial={{ opacity: 0, x: 24 }}
              animate={{ opacity: 1, x: 0, transition: { duration: 0.32, ease: [0.2, 0.7, 0.2, 1] } }}
              exit={{ opacity: 0, x: -24, transition: { duration: 0.18 } }}
            >
              <Outlet />
            </m.div>
          </AnimatePresence>
        </div>
      </div>
    </main>
  )
}

/** The card that holds a form, with a title and a short line under it. */
export function AuthCard({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <div className="card relative overflow-hidden p-7 sm:p-9">
      <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-linear-to-r from-transparent via-indigo-300/60 to-transparent" />
      <h1 className="font-display text-[34px] font-semibold leading-tight">{title}</h1>
      <p className="mt-1.5 text-sm text-slate-400">{subtitle}</p>
      <div className="mt-7">{children}</div>
    </div>
  )
}
