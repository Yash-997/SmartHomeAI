import { useAuth } from '../auth'
import GasAlertsPanel from '../components/GasAlertsPanel'
import HealthCard from '../components/HealthCard'
import OverviewStrip from '../components/OverviewStrip'
import Header from '../components/Header'
import RecommendationsPanel from '../components/RecommendationsPanel'
import SecurityPanel from '../components/SecurityPanel'
import SensorReadingsPanel from '../components/SensorReadingsPanel'
import SystemStatusPanel from '../components/SystemStatusPanel'
import TwinPanel from '../components/TwinPanel'

export default function Dashboard() {
  const { user } = useAuth()
  if (!user) return null

  return (
    <main className="min-h-screen bg-slate-900 p-6 text-slate-100">
      <div className="mx-auto max-w-6xl space-y-6">
        <Header />

        <section className="rounded-xl bg-slate-800 p-6 shadow-lg">
          <p className="text-[11px] font-medium uppercase tracking-wider text-indigo-300">Dashboard</p>
          <h2 className="mt-1 text-2xl font-semibold">Welcome, {user.username}</h2>
          <dl className="mt-3 space-y-1 text-sm text-slate-300">
            <div><dt className="inline text-slate-500">Username: </dt><dd className="inline">{user.username}</dd></div>
            <div><dt className="inline text-slate-500">Email: </dt><dd className="inline">{user.email}</dd></div>
          </dl>
        </section>

        <OverviewStrip />

        <SystemStatusPanel />

        <SensorReadingsPanel />

        <GasAlertsPanel />

        <div className="grid gap-6 lg:grid-cols-2">
          <SecurityPanel />
          <RecommendationsPanel />
        </div>

        <TwinPanel />

        <HealthCard />
      </div>
    </main>
  )
}
