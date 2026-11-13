import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import VoicePanel from './VoicePanel'

const base = {
  recognitionSupported: true, listenState: 'idle' as const, transcript: '', listenError: '', busy: false, onMic: vi.fn(),
  ttsSupported: false, speaking: false, muted: false, onMuted: vi.fn(), onStopSpeaking: vi.fn(), voices: [], voiceURI: null, onVoice: vi.fn(),
  lastAnswer: null, onReplay: vi.fn(),
}

describe('VoicePanel', () => {
  it('only animates the waveform while listening or speaking', () => {
    const { rerender } = render(<VoicePanel {...base} />)
    expect(screen.getByTestId('waveform')).toHaveAttribute('data-active', 'false')
    rerender(<VoicePanel {...base} listenState="listening" />)
    expect(screen.getByTestId('waveform')).toHaveAttribute('data-active', 'true')
    rerender(<VoicePanel {...base} speaking />)
    expect(screen.getByTestId('waveform')).toHaveAttribute('data-active', 'true')
  })

  it('keeps the real error visible and offers retry and typing', async () => {
    const onMic = vi.fn()
    render(<VoicePanel {...base} onMic={onMic} listenState="error" listenError="Speech recognition could not reach the speech service." />)
    expect(screen.getByTestId('voice-status')).toHaveTextContent('could not reach the speech service')
    expect(screen.getByText(/nothing was sent/i)).toBeInTheDocument()
    expect(screen.queryByText('Answered')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onMic).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Type instead' })).toBeInTheDocument()
  })
})
