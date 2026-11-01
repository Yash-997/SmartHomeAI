import { NavLink } from 'react-router-dom'
import { useAuth } from '../auth'

// The active page is marked by colour and a thin accent underline, so it does not rely on colour alone.
const link = ({ isActive }: { isActive: boolean }) =>
  `whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${
    isActive
      ? 'bg-slate-700 text-slate-100 shadow-[inset_0_-2px_0_var(--color-indigo-400)]'
      : 'text-slate-300 hover:bg-slate-800 hover:text-slate-100'
  }`

export default function Header() {
  const { user, logout } = useAuth()
  return (
    <header className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 border-b border-slate-700 pb-4">
      <div className="flex min-w-0 flex-wrap items-center gap-x-5 gap-y-2">
        <h1 className="text-lg font-semibold tracking-tight">AI Smart Home</h1>
        <nav aria-label="Main" className="flex flex-wrap gap-1">
          <NavLink to="/" end className={link}>Dashboard</NavLink>
          <NavLink to="/devices" className={link}>Devices</NavLink>
          <NavLink to="/automation" className={link}>Automation</NavLink>
          <NavLink to="/insights" className={link}>ML Insights</NavLink>
          <NavLink to="/assistant" className={link}>Assistant</NavLink>
        </nav>
      </div>
      <div className="flex items-center gap-3 text-sm text-slate-400">
        <span className="max-w-[10rem] truncate" title={user?.username}>{user?.username}</span>
        <button onClick={logout} className="rounded-md border border-slate-700 bg-slate-800 px-3 py-1.5 text-slate-100 hover:bg-slate-700">
          Log out
        </button>
      </div>
    </header>
  )
}
