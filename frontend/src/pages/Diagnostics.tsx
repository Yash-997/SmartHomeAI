import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import GasAlertsPanel from '../components/GasAlertsPanel'
import HealthCard from '../components/HealthCard'
import SensorReadingsPanel from '../components/SensorReadingsPanel'
import Shell, { PageHeader } from '../components/Shell'
import SystemStatusPanel from '../components/SystemStatusPanel'
import TwinPanel from '../components/TwinPanel'

/** The technical side of the home: backend, broker and database health, device twins, raw sensor values, gas history. */
export default function Diagnostics() {
  const { hash } = useLocation()

  // A link such as /diagnostics#gas scrolls to that section once the page has rendered.
  useEffect(() => {
    if (!hash) return
    document.getElementById(hash.slice(1))?.scrollIntoView({ block: 'start' })
  }, [hash])

  return (
    <Shell>
      <PageHeader title="System Diagnostics" subtitle="Technical details for checking that everything is connected and working." />
      <SystemStatusPanel />
      <SensorReadingsPanel />
      <TwinPanel />
      <div id="gas" className="scroll-mt-6">
        <GasAlertsPanel />
      </div>
      <HealthCard />
    </Shell>
  )
}
