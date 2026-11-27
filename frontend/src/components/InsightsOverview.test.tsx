import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import InsightsOverview from './InsightsOverview'
import { normalizeStatus } from '../insights'
import type { MlStatus } from '../api'

describe('ML Insights overview', () => {
  it('invents no model details when the server reports none', () => {
    vi.stubGlobal('fetch', vi.fn())
    const status = normalizeStatus({ occupancy: {}, anomalies: { active: 0, critical: 0, warning: 0, info: 0, lastScanAt: null }, notice: '' } as unknown as MlStatus)
    render(<MemoryRouter><InsightsOverview status={status} onOpenAnomalies={() => {}} /></MemoryRouter>)
    expect(screen.getByText('Unavailable')).toBeInTheDocument()
    expect(screen.queryByText(/^v\d/)).toBeNull()
    expect(screen.getByText('No active anomalies')).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
