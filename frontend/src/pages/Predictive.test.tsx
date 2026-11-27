// The demonstration page against a mocked backend: nothing here is real data or a real prediction.
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Predictive from './Predictive'
import { SendToDevice } from '../components/PredictivePanels'
import { hhmm, pct, toMinute, type Simulation } from '../predictive'

const sim = vi.hoisted(() => ({ simulation: vi.fn(), act: vi.fn() }))
vi.mock('../predictive', async (importOriginal) => {
  const real = await importOriginal<typeof import('../predictive')>()
  return { ...real, predictiveApi: { ...real.predictiveApi, ...sim } }
})
vi.mock('../api', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api')>()), api: { listDevices: () => Promise.resolve([]) } }))
vi.mock('../auth', () => ({ useAuth: () => ({ user: { username: 'tester' }, logout: () => {} }) }))

const LABEL = 'DATA SOURCE: SYNTHETIC DEMONSTRATION DATA'
const hours = Array.from({ length: 24 }, (_, h) => h / 24)
const routine = { wake: 362, wakeSpread: 20, leave: 573, returnFrom: 1110, returnTo: 1160, days: 22 }
const VIEW: Simulation = {
  mode: 'SIMULATION', dataLabel: LABEL, scenario: 'NIGHT_MODE', now: '2026-12-02T02:00:00', minute: 120,
  today: { wake: 360, leave: 570, back: 1140 }, sustainDays: 0, learned: routine, learnedWeekend: routine,
  deviation: { kind: 'NONE', explanation: 'Within the normal range.', differenceMinutes: null, consecutiveDays: 0 },
  presence: { probability: 0.97, state: 'LIKELY_OCCUPIED', confidence: 0.97 }, arrival: null, arrivalApplicable: false, nightActive: true,
  settings: { nightEnabled: true, nightStart: '22:00:00', nightEnd: '06:00:00', predictiveEnabled: true, onThreshold: 0.75, offThreshold: 0.35,
    arrivalThreshold: 0.65, confidenceThreshold: 0.6, preArrivalMinutes: 60, cooldownSeconds: 60, motionHoldMinutes: 2, preArrivalTimeoutMinutes: 45, predictionMaxAgeSeconds: 180 },
  override: 'NONE', light: { mode: 'off', on: false },
  decision: { type: 'SUPPRESSED_NIGHT_MODE', command: null, reason: 'Motion detected, but lighting suppressed because Night Mode is active.' },
  acknowledgement: 'SIMULATED light: no command was sent to any hardware.', timeline: [{ minute: 0, motion: 1, predicted: 0.98, actual: true }],
  activity: { weekdayPresence: hours, weekendPresence: hours, weekdayMotion: hours, weekendMotion: hours }, log: [],
  lastTrainedAt: '2026-11-28T00:00:00Z', modelVersion: 4, preArrival: { since: null, expired: false },
  dataset: { records: 42750, days: 30, seed: 42, from: '', to: '', arrivals: 21, departures: 21, offlineMinutes: 450, timeZone: 'Asia/Kolkata' },
}

beforeEach(() => {
  sim.simulation.mockReset().mockResolvedValue(VIEW)
  sim.act.mockReset().mockResolvedValue(VIEW)
})

describe('predictive demonstration page', () => {
  it('labels the data, explains Night Mode and draws the prediction charts', async () => {
    render(<MemoryRouter><Predictive /></MemoryRouter>)
    expect(await screen.findByText('SIMULATION MODE')).toBeInTheDocument()
    expect(screen.getByText(new RegExp(LABEL))).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(screen.getByText(VIEW.decision.reason)).toBeInTheDocument()
    for (const chart of [/Weekday resident activity/, /Weekend resident activity/, /Simulated PIR motion/, /Predicted occupancy probability/]) {
      expect(screen.getByRole('img', { name: chart })).toBeInTheDocument()
    }
  })

  it('sends each control to the server instead of computing predictions in the browser', async () => {
    render(<MemoryRouter><Predictive /></MemoryRouter>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Trigger PIR motion' }))
    expect(sim.act).toHaveBeenLastCalledWith('motion', undefined)
    await user.click(screen.getByRole('button', { name: 'Manual ON' }))
    expect(sim.act).toHaveBeenLastCalledWith('override', { override: 'ON' })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Scenario' }), 'MISSED_ROUTINE')
    expect(sim.act).toHaveBeenLastCalledWith('scenario', { scenario: 'MISSED_ROUTINE' })
    await user.click(screen.getByRole('button', { name: 'Delay arrival by 1 hour' }))
    expect(sim.act).toHaveBeenLastCalledWith('today', { wake: 360, leave: 570, back: 1200, sustainDays: 0 })
    await user.click(screen.getByRole('button', { name: /Missed routine \(stays home\)/ }))
    expect(sim.act).toHaveBeenLastCalledWith('today', expect.objectContaining({ leave: -1 }))
    await user.click(screen.getByRole('checkbox', { name: 'Night Mode' }))
    await waitFor(() => expect(sim.act).toHaveBeenLastCalledWith('settings', { settings: { ...VIEW.settings, nightEnabled: false } }))
    for (const name of ['Start', 'Reset']) expect(screen.getByRole('button', { name })).toBeInTheDocument()
  })

  it('formats values without inventing missing ones, and shows no acknowledgement before a command', () => {
    expect(hhmm(1110)).toBe('18:30')
    expect(toMinute('02:05')).toBe(125)
    expect(hhmm(null)).toBe('—')
    expect(pct(null)).toBe('—')
    render(<SendToDevice picker={null} deviceId="" decision={{ type: 'LIGHT_ON', command: 'on', reason: '' }} light={{ mode: 'on', on: true }} />)
    expect(screen.queryByText(/Real command/)).toBeNull()
    expect(screen.getByRole('button', { name: /send "on" to the device/i })).toBeDisabled()
  })
})
