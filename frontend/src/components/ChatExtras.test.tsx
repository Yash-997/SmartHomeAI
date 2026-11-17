import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ChatMessage, Visual } from '../api'
import ChatMessageView from './ChatMessageView'
import VisualView from './VisualView'

const line: Visual = {
  kind: 'line', title: 'Temperature, today', unit: '°C',
  labels: ['2026-11-13T00:00:00Z', '2026-11-13T01:00:00Z', '2026-11-13T02:00:00Z'],
  series: [{ name: 'average', values: [21, null, 23] }, { name: 'min', values: [20, null, 22] }, { name: 'max', values: [22, null, 24] }],
  columns: [], rows: [],
}

function answer(extra: Partial<ChatMessage>): ChatMessage {
  return {
    id: 'm1', role: 'assistant', content: 'Average **22.0 °C** [S1].', status: 'ANSWERED', warnings: [], suggestions: [], createdAt: '2026-11-13T10:00:00Z',
    sources: [{ ref: 'S1', type: 'LIVE_DATA', title: 'Sensor statistics', snippet: 'average 22.0', score: 1, asOf: '2026-11-13T10:00:00Z', deviceId: null, cited: true }],
    ...extra,
  }
}

describe('VisualView', () => {
  it('draws a gap where data is missing and can show the numbers as a table', () => {
    const { container } = render(<VisualView visual={line} />)
    const path = container.querySelector('path[stroke]')?.getAttribute('d') ?? ''
    expect((path.match(/M/g) ?? []).length).toBe(2)          // the line restarts after the missing hour
    fireEvent.click(screen.getByRole('button', { name: 'Show table' }))
    expect(screen.getAllByRole('cell', { name: '–' }).length).toBeGreaterThan(0)   // missing, never 0
  })

  it('renders tables directly', () => {
    render(<VisualView visual={{ kind: 'table', title: 'Latest', unit: '', labels: [], series: [], columns: ['Device', 'Temp'], rows: [['Kitchen', '22.1']] }} />)
    expect(screen.getByRole('cell', { name: 'Kitchen' })).toBeInTheDocument()
  })
})

describe('assistant message extras', () => {
  it('shows the deterministic safety notice, charts, data used and follow-ups', () => {
    const onFollowUp = vi.fn()
    render(
      <MemoryRouter>
        <ChatMessageView latest onSuggestionChange={() => {}} onFollowUp={onFollowUp} message={answer({
          extras: { visuals: [line], actions: [], reports: ['r1'], followUps: ['Compare it with yesterday'], safety: 'Gas alert ACTIVE: Kitchen.',
            tools: [{ tool: 'get_sensor_statistics', arguments: '{"period":"today"}', ok: true, error: null }] },
        })} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Gas alert ACTIVE')
    expect(screen.getByRole('img', { name: /Temperature, today/ })).toBeInTheDocument()
    expect(screen.getByText(/Data used/)).toHaveTextContent('statistics (period: today)')
    expect(screen.getByRole('link', { name: /Open the report/ })).toHaveAttribute('href', '/reports?open=r1')
    fireEvent.click(screen.getByRole('button', { name: 'Compare it with yesterday' }))
    expect(onFollowUp).toHaveBeenCalledWith('Compare it with yesterday')
  })

  it('a proposed device command needs an explicit review and confirmation', () => {
    const onAction = vi.fn()
    const action = { deviceId: 'd1', deviceName: 'Hall', action: 'set_pir_led', value: 'on', description: 'Set the PIR indicator LED of Hall to ON' }
    render(
      <MemoryRouter>
        <ChatMessageView onSuggestionChange={() => {}} onAction={onAction}
          message={answer({ extras: { visuals: [], actions: [action], reports: [], followUps: [], safety: null, tools: [] } })} />
      </MemoryRouter>,
    )
    expect(screen.getByText(/Nothing has been sent/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Review and confirm' }))
    expect(onAction).toHaveBeenCalledWith(action)
  })

  it('labels answers built from data without a model honestly, and old messages without extras still render', () => {
    render(<MemoryRouter><ChatMessageView onSuggestionChange={() => {}} message={answer({ status: 'NOT_CONFIGURED', extras: undefined })} /></MemoryRouter>)
    expect(screen.getByText(/answer built directly from your data/)).toBeInTheDocument()
  })
})
