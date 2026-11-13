import SecurityPanel from '../components/SecurityPanel'
import Shell, { PageHeader } from '../components/Shell'

export default function Security() {
  return (
    <Shell>
      <PageHeader title="Security" subtitle="Choose how your home is watched and review what happened." />
      <SecurityPanel />
    </Shell>
  )
}
