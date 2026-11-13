import { useEffect, useRef, useState, type ComponentType, type ReactNode, type SVGProps } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { useAuth } from '../auth'
import {
  BoltIcon, ChatIcon, CloseIcon, DevicesIcon, HomeIcon, LogoutIcon, MenuIcon, PulseIcon, ShieldIcon, SparkIcon,
} from '../ui/icons'

type Item = { to: string; label: string; icon: ComponentType<SVGProps<SVGSVGElement>>; end?: boolean }

const ITEMS: Item[] = [
  { to: '/', label: 'Dashboard', icon: HomeIcon, end: true },
  { to: '/devices', label: 'Devices', icon: DevicesIcon },
  { to: '/automation', label: 'Automation', icon: BoltIcon },
  { to: '/security', label: 'Security', icon: ShieldIcon },
  { to: '/insights', label: 'ML Insights', icon: SparkIcon },
  { to: '/assistant', label: 'Assistant', icon: ChatIcon },
  { to: '/diagnostics', label: 'System Diagnostics', icon: PulseIcon },
]
/** The four sections kept on the phone's bottom bar; the rest live under "More". */
const PRIMARY = ['/', '/devices', '/security', '/assistant']

function Logo() {
  return (
    <div className="flex items-center gap-2.5">
      <span aria-hidden="true" className="grid h-8 w-8 place-items-center rounded-lg bg-indigo-400 text-slate-950">
        <HomeIcon width={18} height={18} strokeWidth={2.2} />
      </span>
      <span className="text-[15px] font-semibold tracking-tight">AI Smart Home</span>
    </div>
  )
}

const side = ({ isActive }: { isActive: boolean }) =>
  `group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium ${
    isActive ? 'bg-slate-800 text-slate-100' : 'text-slate-400 hover:bg-slate-800/60 hover:text-slate-100'
  }`

const tab = ({ isActive }: { isActive: boolean }) =>
  `flex flex-1 flex-col items-center gap-1 rounded-lg px-1 py-1.5 text-[11px] font-medium ${isActive ? 'text-indigo-400' : 'text-slate-400 hover:text-slate-100'}`

function UserBlock({ compact = false }: { compact?: boolean }) {
  const { user, logout } = useAuth()
  const initial = (user?.username ?? '?').slice(0, 1).toUpperCase()
  return (
    <div className={`flex items-center gap-3 ${compact ? '' : 'border-t border-slate-800 pt-4'}`}>
      <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-slate-700 text-sm font-semibold text-slate-100">{initial}</span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium" title={user?.username}>{user?.username}</p>
        {user?.email && <p className="truncate text-xs text-slate-500" title={user.email}>{user.email}</p>}
      </div>
      <button type="button" onClick={logout} aria-label="Log out" title="Log out"
        className="btn-secondary grid h-9 w-9 shrink-0 place-items-center rounded-lg">
        <LogoutIcon width={18} height={18} />
      </button>
    </div>
  )
}

/** The frame of every signed-in page: a sidebar on desktop, a top bar and a bottom tab bar on phones. */
export default function Shell({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  const location = useLocation()
  // The menu is open for one specific page: navigating elsewhere closes it without an effect.
  const [openFor, setOpenFor] = useState<string | null>(null)
  const more = openFor === location.pathname
  const setMore = (open: boolean) => setOpenFor(open ? location.pathname : null)
  const moreRef = useRef<HTMLButtonElement>(null)
  const sheetRef = useRef<HTMLDivElement>(null)

  // Escape closes the menu and gives focus back to its button.
  useEffect(() => {
    if (!more) return
    sheetRef.current?.querySelector<HTMLElement>('a,button')?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMore(false)
        moreRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line
  }, [more])

  const onMoreRoute = !PRIMARY.includes(location.pathname)

  return (
    <div className="min-h-screen text-slate-100">
      <a href="#content" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[60] focus:rounded-lg focus:bg-indigo-400 focus:px-3 focus:py-2 focus:text-sm focus:font-semibold focus:text-slate-950">
        Skip to content
      </a>

      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col gap-6 border-r border-slate-800 bg-slate-950/90 p-5 backdrop-blur lg:flex">
        <Logo />
        <nav aria-label="Main" className="flex flex-1 flex-col gap-1">
          {ITEMS.map(({ to, label, icon: I, end }) => (
            <NavLink key={to} to={to} end={end} className={side}>
              {({ isActive }) => (
                <>
                  <span aria-hidden="true" className={`absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-indigo-400 transition-opacity ${isActive ? 'opacity-100' : 'opacity-0'}`} />
                  <I className={isActive ? 'text-indigo-400' : ''} />
                  {label}
                </>
              )}
            </NavLink>
          ))}
        </nav>
        <UserBlock />
      </aside>

      {/* Phone top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-slate-800 bg-slate-950/90 px-4 py-3 backdrop-blur lg:hidden">
        <Logo />
        <button type="button" onClick={() => setMore(true)} aria-label="Account and more" className="btn-secondary grid h-9 w-9 place-items-center rounded-lg">
          <MenuIcon />
        </button>
      </header>

      <main id="content" tabIndex={-1} className="px-4 pb-28 pt-6 outline-none sm:px-6 lg:pb-12 lg:pl-[17rem] lg:pr-10 lg:pt-10 2xl:pr-12">
        <div className={`mx-auto space-y-6 lg:space-y-8 ${wide ? 'max-w-[1440px]' : 'max-w-[1280px]'}`}>{children}</div>
      </main>

      {/* Phone bottom bar */}
      <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-30 flex gap-1 border-t border-slate-800 bg-slate-950/95 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-1.5 backdrop-blur lg:hidden">
        {ITEMS.filter((i) => PRIMARY.includes(i.to)).map(({ to, label, icon: I, end }) => (
          <NavLink key={to} to={to} end={end} className={tab}>
            <I />
            {label}
          </NavLink>
        ))}
        <button ref={moreRef} type="button" onClick={() => setMore(true)} aria-haspopup="dialog" aria-expanded={more}
          className={`flex flex-1 flex-col items-center gap-1 rounded-lg px-1 py-1.5 text-[11px] font-medium ${onMoreRoute ? 'text-indigo-400' : 'text-slate-400 hover:text-slate-100'}`}>
          <MenuIcon />
          More
        </button>
      </nav>

      {more && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="More">
          <button type="button" aria-label="Close menu" tabIndex={-1} className="absolute inset-0 bg-black/60" onClick={() => setMore(false)} />
          <div ref={sheetRef} className="absolute inset-x-0 bottom-0 space-y-4 rounded-t-2xl border-t border-slate-700 bg-slate-800 p-4 pb-[max(1rem,env(safe-area-inset-bottom))] shadow-lg">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold">More</h2>
              <button type="button" onClick={() => { setMore(false); moreRef.current?.focus() }} aria-label="Close" className="btn-secondary grid h-8 w-8 place-items-center rounded-lg"><CloseIcon width={16} height={16} /></button>
            </div>
            <ul className="grid gap-1">
              {ITEMS.filter((i) => !PRIMARY.includes(i.to)).map(({ to, label, icon: I }) => (
                <li key={to}>
                  <NavLink to={to} className={side}><I />{label}</NavLink>
                </li>
              ))}
            </ul>
            <UserBlock compact />
          </div>
        </div>
      )}
    </div>
  )
}

/** Title row of a page: one heading, an optional one-line subtitle and the page's main action. */
export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{title}</h1>
        {subtitle && <p className="mt-1.5 max-w-2xl text-sm text-slate-400">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}
