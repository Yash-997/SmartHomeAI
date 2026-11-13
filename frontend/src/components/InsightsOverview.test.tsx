// The overview must never show a prediction before a valid model exists, and must not hide a low-quality warning.
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MlStatus } from '../api'
import { normalizeStatus } from '../insights'
import InsightsOverview from './InsightsOverview'

const predictNow = vi.fn()
vi.mock('../api', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api')>()), api: { predictNow: (...a: unknown[]) => predictNow(...a) } }))

const base = normalizeStatus({ occupancy: {}, anomalies: { active: 0, critical: 0, warning: 0, info: 0, lastScanAt: null }, notice: '' } as unknown as MlStatus)

describe('InsightsOverview', () => {
  it('shows no prediction and does not ask for one without a model', () => {
    render(<InsightsOverview deviceId="d1" status={base} onOpenAnomalies={() => {}} />)
    expect(screen.getByText(/No prediction yet/)).toBeInTheDocument()
    expect(predictNow).not.toHaveBeenCalled()
  })

  it('warns when the model is low quality', () => {
    const status = { ...base, occupancy: { ...base.occupancy, state: 'LOW_QUALITY' as const } }
    render(<InsightsOverview deviceId="d1" status={status} onOpenAnomalies={() => {}} />)
    expect(screen.getByRole('alert')).toHaveTextContent(/low quality/i)
  })

  it('calls out critical anomalies', () => {
    const status = { ...base, anomalies: { ...base.anomalies, active: 2, critical: 1 } }
    render(<InsightsOverview deviceId="d1" status={status} onOpenAnomalies={() => {}} />)
    expect(screen.getByRole('alert')).toHaveTextContent(/1 critical anomaly is active/)
  })
})
