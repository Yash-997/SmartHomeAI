import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReportDetail } from '../api'
import Reports from './Reports'

const apiMock = vi.hoisted(() => ({ reports: vi.fn(), report: vi.fn(), createReport: vi.fn(), deleteReport: vi.fn(), listDevices: vi.fn() }))
const pdfMock = vi.hoisted(() => vi.fn())
vi.mock('../api', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api')>()), api: apiMock, downloadReportPdf: pdfMock }))
vi.mock('../auth', () => ({ useAuth: () => ({ user: { username: 'tester' }, logout: () => {} }) }))

const DETAIL: ReportDetail = {
  id: 'r1', kind: 'WEEKLY', title: 'Weekly report: the last 7 days', periodFrom: '2026-11-06T10:00:00Z', periodTo: '2026-11-13T10:00:00Z',
  timeZone: 'UTC', deviceId: null, summary: '12 readings', createdAt: '2026-11-13T10:00:00Z',
  content: {
    title: 'Weekly report: the last 7 days', periodLabel: 'the last 7 days', scope: 'all devices', generatedAt: '2026-11-13T10:00:00Z',
    sections: [
      { heading: 'Executive summary', paragraphs: ['12 readings were stored.'], bullets: [], visuals: [] },
      { heading: 'Recommendations', paragraphs: [], bullets: ['Humidity averaged 71.0 %.'], visuals: [] },
    ],
    notes: ['Gas values are raw MQ-2 sensor readings, not ppm.'],
  },
}

beforeEach(() => {
  Object.values(apiMock).forEach((f) => f.mockReset())
  pdfMock.mockReset()
  apiMock.reports.mockResolvedValue([])
  apiMock.listDevices.mockResolvedValue([])
})

describe('Reports page', () => {
  it('generates a weekly report, previews it and downloads the PDF', async () => {
    apiMock.createReport.mockResolvedValue(DETAIL)
    render(<MemoryRouter><Reports /></MemoryRouter>)
    await screen.findByText('No reports yet.')

    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }))

    await screen.findByRole('heading', { name: 'Weekly report: the last 7 days' })
    expect(apiMock.createReport).toHaveBeenCalledWith(expect.objectContaining({ kind: 'weekly' }))
    expect(screen.getByText('Humidity averaged 71.0 %.')).toBeInTheDocument()
    expect(apiMock.report).not.toHaveBeenCalled()          // the new report is shown without fetching it again
    fireEvent.click(screen.getByRole('button', { name: 'Download PDF' }))
    await waitFor(() => expect(pdfMock).toHaveBeenCalledWith('r1', expect.stringMatching(/\.pdf$/)))
  })

  it('custom reports send their dates', async () => {
    apiMock.createReport.mockResolvedValue(DETAIL)
    render(<MemoryRouter><Reports /></MemoryRouter>)
    fireEvent.click(screen.getByRole('radio', { name: 'Custom' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-07' } })
    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }))
    await waitFor(() => expect(apiMock.createReport).toHaveBeenCalledWith(expect.objectContaining({ kind: 'custom', from: '2026-11-01', to: '2026-11-07' })))
  })

  it('opens a report from the link the assistant gives', async () => {
    apiMock.report.mockResolvedValue(DETAIL)
    render(<MemoryRouter initialEntries={['/reports?open=r1']}><Reports /></MemoryRouter>)
    await screen.findByRole('heading', { name: 'Weekly report: the last 7 days' })
    expect(apiMock.report).toHaveBeenCalledWith('r1')
  })

  it('shows a server error instead of failing silently', async () => {
    const { ApiError } = await import('../api')
    apiMock.createReport.mockRejectedValue(new ApiError(429, 'Too many reports in a minute.'))
    render(<MemoryRouter><Reports /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many reports')
  })
})
