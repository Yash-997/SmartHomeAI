import { describe, expect, it } from 'vitest'
import type { Device, ReportedState, Telemetry } from './api'
import { gasView, motionView } from './sensorStatus'

function telemetry(t: Partial<Telemetry>): Telemetry {
  return { receivedAt: '2026-10-31T08:00:00Z', deviceTimestamp: null, temperatureC: null, humidityPct: null, motion: null,
    gasRaw: null, ageSeconds: 5, stale: false, ...t }
}

function state(gasWarning: boolean, stale = false): ReportedState {
  return { pirLed: { mode: 'auto', on: false }, gasLed: { mode: 'auto', on: gasWarning }, gasWarning, lastCommandId: null,
    reportedAt: '2026-10-31T08:00:00Z', ageSeconds: 5, stale }
}

function device(t: Telemetry | null, rs: ReportedState | null = null): Device {
  return { id: 'd1', deviceId: 'esp32-x', name: 'Hall', status: 'ACTIVE', registeredAt: '2026-10-01T00:00:00Z',
    connection: 'ONLINE', lastSeenAt: null, lastSeenAgeSeconds: 5, staleAfterSeconds: 90, latestTelemetry: t, outputs: [],
    reportedState: rs, recentCommands: [] } as Device
}

describe('gasView', () => {
  it('shows the real reading when the sensor reads normally', () => {
    const v = gasView(device(telemetry({ gasRaw: 812 }), state(false)), false)
    expect(v).toMatchObject({ raw: 812, tone: 'ok', warning: false })
  })

  it('never invents a value when the latest report has no gas reading', () => {
    const v = gasView(device(telemetry({ gasRaw: null, motion: false })), false)
    expect(v.raw).toBeNull()
    expect(v.status).toMatch(/warming up or not readable/)
  })

  it('flags a reading pinned at 0 as a sensor problem', () => {
    expect(gasView(device(telemetry({ gasRaw: 0 })), false)).toMatchObject({ raw: 0, tone: 'warn' })
  })

  it('shows an active device gas warning as an alarm', () => {
    expect(gasView(device(telemetry({ gasRaw: 2900 }), state(true)), false)).toMatchObject({ tone: 'alarm', warning: true })
  })

  it('does not trust an old state report or present stale values as current', () => {
    expect(gasView(device(telemetry({ gasRaw: 2900 }), state(true, true)), false).warning).toBeNull()
    expect(gasView(device(telemetry({ gasRaw: 700 })), true)).toMatchObject({ raw: 700, tone: 'muted' })
  })

  it('handles a device that has never reported', () => {
    expect(gasView(device(null), false)).toMatchObject({ raw: null, status: 'No readings received yet' })
  })
})

describe('motionView', () => {
  it('shows motion and no motion', () => {
    expect(motionView(device(telemetry({ motion: true })), false).label).toBe('Motion detected')
    expect(motionView(device(telemetry({ motion: false })), false).label).toBe('No motion')
  })

  it('marks stale and missing motion readings', () => {
    expect(motionView(device(telemetry({ motion: true })), true).label).toBe('Motion (stale)')
    expect(motionView(device(telemetry({ motion: null })), false)).toEqual({ motion: null, label: 'No reading' })
  })
})
