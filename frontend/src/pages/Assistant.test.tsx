// Component tests for the dual-mode assistant. The backend is mocked (api module) and so are the browser speech
// APIs (see test/speechMocks.ts): these prove what the page does with them, not that a real microphone, voice,
// language model or device works.
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssistantStatus, ChatMessage, ChatReply, Command, Device } from '../api'
import {
  denyMicrophonePermission,
  FakeRecognition,
  installRecognition,
  installSynthesis,
  removeRecognition,
  removeSynthesis,
  resetPermissions,
} from '../test/speechMocks'
import Assistant from './Assistant'

const apiMock = vi.hoisted(() => ({
  assistantStatus: vi.fn(),
  conversations: vi.fn(),
  conversationMessages: vi.fn(),
  deleteConversation: vi.fn(),
  chat: vi.fn(),
  knowledgeDocs: vi.fn(),
  listDevices: vi.fn(),
  sendCommand: vi.fn(),
  getCommand: vi.fn(),
}))

vi.mock('../api', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api')>()), api: apiMock }))
vi.mock('../auth', () => ({ useAuth: () => ({ user: { username: 'tester' }, logout: () => {} }) }))

const STATUS: AssistantStatus = {
  llm: { configured: true, provider: 'groq', model: 'llama-3.3-70b-versatile', note: '' },
  embedding: { configured: true, provider: 'local', model: 'hash', note: '' },
  index: { yourChunks: 4, sharedChunks: 10, documents: 0 },
  limits: { maxMessageChars: 1000, maxDocumentChars: 20000, maxDocuments: 10, topK: 6 },
  notice: 'Answers come from your stored records.',
}

let n = 0
function msg(role: 'user' | 'assistant', content: string, extra: Partial<ChatMessage> = {}): ChatMessage {
  return { id: `m${++n}`, role, content, status: role === 'assistant' ? 'ANSWERED' : null, sources: [], warnings: [], suggestions: [], createdAt: new Date().toISOString(), ...extra }
}

function reply(question: string, answer: string, conversationId = 'conv-1'): ChatReply {
  return {
    conversationId,
    conversationTitle: question,
    userMessage: msg('user', question),
    assistantMessage: msg('assistant', answer, {
      sources: [{ ref: 'S1', type: 'DEVICE_STATUS', title: 'Living room node', snippet: 'temperature 24.6 C', score: 0.91, asOf: new Date().toISOString(), deviceId: 'dev-1', cited: true }],
    }),
  }
}

function device(over: Partial<Device> = {}): Device {
  return {
    id: 'dev-1', deviceId: 'esp32-aabbccddeeff', name: 'Living room node', status: 'ACTIVE', registeredAt: '', connection: 'ONLINE',
    lastSeenAt: null, lastSeenAgeSeconds: 3, staleAfterSeconds: 90, latestTelemetry: null,
    outputs: [{ action: 'set_pir_led', label: 'PIR LED', values: ['auto', 'on', 'off'] }, { action: 'set_gas_led', label: 'Gas LED', values: ['auto', 'on'] }],
    reportedState: null, recentCommands: [], ...over,
  }
}

function command(status: Command['status'], over: Partial<Command> = {}): Command {
  return { id: 'cmd-1', action: 'set_pir_led', value: 'on', status, createdAt: '', expiresAt: new Date(Date.now() + 30000).toISOString(), sentAt: null, completedAt: null, detail: null, origin: null, ...over }
}

function renderPage() {
  return render(<MemoryRouter><Assistant /></MemoryRouter>)
}

const textbox = () => screen.getByRole('textbox', { name: 'Your question' })
const toVoice = async (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('radio', { name: 'Voice' }))
const toText = async (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('radio', { name: 'Text' }))
const mic = () => screen.getByRole('button', { name: /start listening|stop listening/i })

let synth: ReturnType<typeof installSynthesis>

beforeEach(() => {
  n = 0
  Object.values(apiMock).forEach((f) => f.mockReset())
  apiMock.assistantStatus.mockResolvedValue(STATUS)
  apiMock.conversations.mockResolvedValue([])
  apiMock.knowledgeDocs.mockResolvedValue([])
  apiMock.conversationMessages.mockResolvedValue([])
  installRecognition()
  synth = installSynthesis()
  resetPermissions()
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  removeRecognition()
  removeSynthesis()
})

describe('text mode', () => {
  it('1. submits a typed question to the existing assistant endpoint', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('What is the current temperature?', 'It is 24.6 C [S1].'))
    renderPage()

    await user.type(textbox(), 'What is the current temperature?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    await waitFor(() => expect(apiMock.chat).toHaveBeenCalledTimes(1))
    expect(apiMock.chat).toHaveBeenCalledWith('What is the current temperature?', undefined)
    expect(apiMock.sendCommand).not.toHaveBeenCalled()
  })

  it('2. shows the assistant answer with its cited sources, and does not speak typed answers', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('What is the current temperature?', 'It is 24.6 C [S1].'))
    renderPage()

    await user.type(textbox(), 'What is the current temperature?{Enter}')

    expect(await screen.findByText('It is 24.6 C [S1].')).toBeInTheDocument()
    expect(screen.getByText('Sources (1, 1 cited)')).toBeInTheDocument()
    expect(screen.getByText('temperature 24.6 C')).toBeInTheDocument()
    expect(textbox()).toHaveValue('')
    expect(synth.speak).not.toHaveBeenCalled()
  })

  it('shows a loading indicator while the answer is generated', async () => {
    const user = userEvent.setup()
    let resolve!: (r: ChatReply) => void
    apiMock.chat.mockReturnValue(new Promise<ChatReply>((r) => (resolve = r)))
    renderPage()

    await user.type(textbox(), 'What is the humidity?{Enter}')
    expect(await screen.findByText('Searching your records and thinking...')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Asking...' })).toBeDisabled()

    await act(async () => resolve(reply('What is the humidity?', 'Humidity is 51 %.')))
    expect(await screen.findByText('Humidity is 51 %.')).toBeInTheDocument()
  })

  it('8. prevents duplicate submissions while a question is in flight', async () => {
    const user = userEvent.setup()
    let resolve!: (r: ChatReply) => void
    apiMock.chat.mockReturnValue(new Promise<ChatReply>((r) => (resolve = r)))
    renderPage()

    await user.type(textbox(), 'What is the humidity?')
    await user.keyboard('{Enter}{Enter}')
    await user.click(screen.getByRole('button', { name: /Ask/ }))

    expect(apiMock.chat).toHaveBeenCalledTimes(1)
    await act(async () => resolve(reply('What is the humidity?', 'Humidity is 51 %.')))
    expect(apiMock.chat).toHaveBeenCalledTimes(1)
  })

  it('lets the user dictate in text mode: the transcript fills the box and is sent only when they press Ask', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('what is the humidity', 'Humidity is 51 %.'))
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Dictate with the microphone' }))
    const rec = FakeRecognition.latest()
    await act(async () => rec.hear('what is the', false))
    expect(screen.getByTestId('dictation-preview')).toHaveTextContent('what is the')
    await act(async () => {
      rec.hear('what is the humidity')
      rec.end()
    })

    expect(textbox()).toHaveValue('what is the humidity')
    expect(apiMock.chat).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    await waitFor(() => expect(apiMock.chat).toHaveBeenCalledWith('what is the humidity', undefined))
  })
})

describe('switching modes', () => {
  it('3. switches between text and voice without submitting anything', async () => {
    const user = userEvent.setup()
    renderPage()

    await toVoice(user)
    expect(mic()).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Voice' })).toHaveAttribute('aria-checked', 'true')
    expect(textbox()).toBeInTheDocument() // typing still works in voice mode

    await toText(user)
    expect(screen.queryByRole('button', { name: /start listening/i })).not.toBeInTheDocument()
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it('4. keeps the conversation and its context across modes', async () => {
    const user = userEvent.setup()
    apiMock.chat
      .mockResolvedValueOnce(reply('What is the temperature?', 'It is 24.6 C [S1].'))
      .mockResolvedValueOnce(reply('And the humidity?', 'Humidity is 51 % [S1].'))
    renderPage()

    await user.type(textbox(), 'What is the temperature?{Enter}')
    await screen.findByText('It is 24.6 C [S1].')

    await toVoice(user)
    expect(screen.getByText('It is 24.6 C [S1].')).toBeInTheDocument()
    await user.type(textbox(), 'And the humidity?{Enter}')
    await screen.findByText('Humidity is 51 % [S1].')
    expect(apiMock.chat).toHaveBeenLastCalledWith('And the humidity?', 'conv-1')

    await toText(user)
    expect(screen.getByText('It is 24.6 C [S1].')).toBeInTheDocument()
    expect(screen.getByText('Humidity is 51 % [S1].')).toBeInTheDocument()
    expect(apiMock.chat).toHaveBeenCalledTimes(2)
  })

  it('a half-heard sentence is discarded, not submitted, when the mode changes', async () => {
    const user = userEvent.setup()
    renderPage()
    await toVoice(user)
    await user.click(mic())
    const rec = FakeRecognition.latest()
    await act(async () => rec.hear('what is the', false))

    await toText(user)
    expect(rec.aborted).toBe(true)
    expect(apiMock.chat).not.toHaveBeenCalled()
    expect(textbox()).toHaveValue('')
  })
})

describe('voice mode: speech recognition', () => {
  it('5. explains a denied microphone permission and sends nothing', async () => {
    const user = userEvent.setup()
    renderPage()
    await toVoice(user)
    await user.click(mic())
    await act(async () => FakeRecognition.latest().fail('not-allowed'))

    expect(screen.getByTestId('voice-status')).toHaveTextContent('Microphone access is blocked')
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it('5b. does not even start the recogniser when the permission is already denied', async () => {
    denyMicrophonePermission()
    const user = userEvent.setup()
    renderPage()
    await toVoice(user)
    await user.click(mic())

    expect(await screen.findByText(/Microphone access is blocked/)).toBeInTheDocument()
    expect(FakeRecognition.instances).toHaveLength(0)
  })

  it('6. transcribes live, submits the final transcript once, then shows and speaks the answer', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('what is the current temperature', 'Living room node is at 24.6 C [S1].'))
    renderPage()
    await toVoice(user)

    expect(FakeRecognition.instances).toHaveLength(0) // nothing listens before the user taps
    await user.click(mic())
    const rec = FakeRecognition.latest()
    expect(rec.started).toBe(true)
    expect(screen.getByTestId('voice-status')).toHaveTextContent('Listening')

    await act(async () => rec.hear('what is the', false))
    expect(screen.getByTestId('live-transcript')).toHaveTextContent('what is the')
    await act(async () => {
      rec.hear('what is the current temperature')
      rec.end()
    })

    await waitFor(() => expect(apiMock.chat).toHaveBeenCalledWith('what is the current temperature', undefined))
    expect(await screen.findByLabelText('Latest answer')).toHaveTextContent('Living room node is at 24.6 C.')
    expect(synth.spokenText()).toBe('Living room node is at 24.6 C.')
  })

  it('7. reports a recognition failure and sends nothing', async () => {
    const user = userEvent.setup()
    renderPage()
    await toVoice(user)
    await user.click(mic())
    await act(async () => FakeRecognition.latest().fail('network'))

    expect(screen.getByTestId('voice-status')).toHaveTextContent("could not reach the browser's speech service")
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it('tells the user when nothing was heard', async () => {
    const user = userEvent.setup()
    renderPage()
    await toVoice(user)
    await user.click(mic())
    await act(async () => FakeRecognition.latest().end())

    expect(screen.getByTestId('voice-status')).toHaveTextContent("didn't hear anything")
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it('8b. one spoken sentence is submitted once, even if the browser reports the end twice', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('what is the humidity', 'Humidity is 51 %.'))
    renderPage()
    await toVoice(user)
    await user.click(mic())
    const rec = FakeRecognition.latest()
    await act(async () => {
      rec.hear('what is the humidity')
      rec.end()
      rec.end()
    })
    await waitFor(() => expect(screen.getByLabelText('Latest answer')).toHaveTextContent('Humidity is 51 %.'))
    expect(apiMock.chat).toHaveBeenCalledTimes(1)
  })

  it('stops listening by itself after a pause instead of keeping the microphone open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderPage()
      await toVoice(user)
      await user.click(mic())
      const rec = FakeRecognition.latest()
      await act(async () => {
        vi.advanceTimersByTime(9000)
      })
      expect(rec.stopped).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('11. falls back to typing when the browser has no speech recognition', async () => {
    removeRecognition()
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('What is the humidity?', 'Humidity is 51 %.'))
    renderPage()

    expect(screen.queryByRole('button', { name: 'Dictate with the microphone' })).not.toBeInTheDocument()
    await toVoice(user)
    expect(screen.getByTestId('voice-status')).toHaveTextContent('Voice input is not supported in this browser')
    expect(mic()).toBeDisabled()

    await user.type(textbox(), 'What is the humidity?{Enter}')
    expect(await screen.findAllByText('Humidity is 51 %.')).not.toHaveLength(0)
  })

  it('11b. without speech synthesis the answer is still shown as text', async () => {
    removeSynthesis()
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('What is the humidity?', 'Humidity is 51 %.'))
    renderPage()
    await toVoice(user)
    expect(screen.getByText(/cannot read answers aloud/)).toBeInTheDocument()
    await user.type(textbox(), 'What is the humidity?{Enter}')
    expect(await screen.findByLabelText('Latest answer')).toHaveTextContent('Humidity is 51 %.')
  })
})

describe('voice mode: speech output', () => {
  async function askByVoice(user: ReturnType<typeof userEvent.setup>, answer: string) {
    apiMock.chat.mockResolvedValue(reply('what is the temperature', answer))
    renderPage()
    await toVoice(user)
    await user.click(mic())
    await act(async () => {
      FakeRecognition.latest().hear('what is the temperature')
      FakeRecognition.latest().end()
    })
    await screen.findByLabelText('Latest answer')
  }

  it('9. speaks the answer without citation labels and splits long answers', async () => {
    const user = userEvent.setup()
    const long = Array.from({ length: 10 }, (_, i) => `Reading ${i + 1} from the living room sensor was within the normal range [S${i + 1}].`).join(' ')
    await askByVoice(user, long)

    expect(synth.speak.mock.calls.length).toBeGreaterThan(1)
    expect(synth.spokenText()).not.toMatch(/\[S\d+\]/)
    expect(synth.spokenText()).toContain('Reading 10 from the living room sensor was within the normal range.')
    expect(screen.getByRole('button', { name: 'Stop speaking' })).toBeEnabled()
  })

  it('10. stops speaking immediately', async () => {
    const user = userEvent.setup()
    await askByVoice(user, 'It is 24.6 C [S1].')
    synth.cancel.mockClear()

    await user.click(screen.getByRole('button', { name: 'Stop speaking' }))
    expect(synth.cancel).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Stop speaking' })).toBeDisabled()
  })

  it('stops the previous answer before speaking a new one, and when the user starts talking', async () => {
    const user = userEvent.setup()
    await askByVoice(user, 'First answer.')
    synth.cancel.mockClear()

    await user.click(mic())
    expect(synth.cancel).toHaveBeenCalled() // the microphone never hears our own voice
    apiMock.chat.mockResolvedValue(reply('and the humidity', 'Second answer.', 'conv-1'))
    synth.cancel.mockClear()
    await act(async () => {
      FakeRecognition.latest().hear('and the humidity')
      FakeRecognition.latest().end()
    })
    await waitFor(() => expect(screen.getByLabelText('Latest answer')).toHaveTextContent('Second answer.'))
    expect(synth.cancel).toHaveBeenCalled()
    expect(synth.spokenText()).toBe('Second answer.')
  })

  it('muting stops speech and keeps answers silent', async () => {
    const user = userEvent.setup()
    await askByVoice(user, 'It is 24.6 C.')
    await user.click(screen.getByRole('button', { name: 'Mute' }))
    expect(synth.cancel).toHaveBeenCalled()
    synth.speak.mockClear()

    apiMock.chat.mockResolvedValue(reply('and the humidity', 'Humidity is 51 %.', 'conv-1'))
    await user.type(textbox(), 'and the humidity{Enter}')
    await waitFor(() => expect(screen.getByLabelText('Latest answer')).toHaveTextContent('Humidity is 51 %.'))
    expect(synth.speak).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Unmute' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('device commands by voice or text', () => {
  async function sayInVoiceMode(user: ReturnType<typeof userEvent.setup>, text: string) {
    await user.click(mic())
    await act(async () => {
      FakeRecognition.latest().hear(text)
      FakeRecognition.latest().end()
    })
  }

  it('asks for confirmation, sends through the command API only after "yes", and reports success only after the device acknowledges', async () => {
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device()])
    apiMock.sendCommand.mockResolvedValue(command('SENT'))
    apiMock.getCommand.mockResolvedValueOnce(command('SENT')).mockResolvedValue(command('ACKNOWLEDGED'))
    renderPage()
    await toVoice(user)

    await sayInVoiceMode(user, 'Turn on the PIR indicator LED')
    const card = await screen.findByLabelText('Device command')
    expect(within(card).getByText('Turn the PIR indicator LED on')).toBeInTheDocument()
    expect(within(card).getByText(/Device: Living room node/)).toBeInTheDocument()
    expect(apiMock.sendCommand).not.toHaveBeenCalled()
    expect(apiMock.chat).not.toHaveBeenCalled() // a command never goes to the language model
    expect(synth.spokenText()).toContain('Please confirm')

    await sayInVoiceMode(user, 'yes')
    await waitFor(() => expect(apiMock.sendCommand).toHaveBeenCalledWith('dev-1', 'set_pir_led', 'on'))
    expect(await within(card).findByText('Sent, waiting for the device to confirm...')).toBeInTheDocument()
    expect(within(card).queryByText(/^Done/)).not.toBeInTheDocument()

    expect(await within(card).findByText('Confirmed by device', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(within(card).getByText(/Done\. Living room node confirmed the change/)).toBeInTheDocument()
    expect(apiMock.getCommand).toHaveBeenCalledWith('dev-1', 'cmd-1')
    expect(synth.spokenText()).toContain('Done. Living room node confirmed the change')
    expect(apiMock.sendCommand).toHaveBeenCalledTimes(1)
  })

  it('never claims success when the device rejects the command', async () => {
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device()])
    apiMock.sendCommand.mockResolvedValue(command('SENT'))
    apiMock.getCommand.mockResolvedValue(command('FAILED', { detail: 'rejected: clock not set' }))
    renderPage()

    await user.type(textbox(), 'Turn on the PIR indicator LED{Enter}')
    const card = await screen.findByLabelText('Device command')
    await user.click(within(card).getByRole('button', { name: 'Confirm and send' }))

    expect(await within(card).findByText('Failed', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(within(card).getByText(/did not apply it: rejected: clock not set/)).toBeInTheDocument()
    expect(within(card).queryByText(/Done/)).not.toBeInTheDocument()
  })

  it('cancelling sends nothing', async () => {
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device()])
    renderPage()
    await toVoice(user)
    await sayInVoiceMode(user, 'Set the PIR indicator to automatic mode')
    const card = await screen.findByLabelText('Device command')
    expect(within(card).getByText('Set the PIR indicator LED to automatic')).toBeInTheDocument()

    await sayInVoiceMode(user, 'no')
    expect(await within(card).findByText('Cancelled: nothing was sent.')).toBeInTheDocument()
    expect(apiMock.sendCommand).not.toHaveBeenCalled()
  })

  it('shows the server refusal when the device is not the user\'s or is offline (authorization stays on the backend)', async () => {
    const { ApiError } = await vi.importActual<typeof import('../api')>('../api')
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device()])
    apiMock.sendCommand.mockRejectedValue(new ApiError(404, 'Device not found'))
    renderPage()

    await user.type(textbox(), 'Turn off the PIR indicator LED{Enter}')
    const card = await screen.findByLabelText('Device command')
    await user.click(within(card).getByRole('button', { name: 'Confirm and send' }))

    expect(await within(card).findByRole('alert')).toHaveTextContent('Device not found')
    expect(within(card).queryByText(/Done|Confirmed/)).not.toBeInTheDocument()
  })

  it('makes the user pick a device when several could do it', async () => {
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device(), device({ id: 'dev-2', name: 'Hallway node' })])
    apiMock.sendCommand.mockResolvedValue(command('ACKNOWLEDGED'))
    renderPage()

    await user.type(textbox(), 'Turn on the PIR indicator LED{Enter}')
    const card = await screen.findByLabelText('Device command')
    const confirmBtn = within(card).getByRole('button', { name: 'Confirm and send' })
    expect(confirmBtn).toBeDisabled()

    await user.selectOptions(within(card).getByRole('combobox'), 'dev-2')
    await user.click(confirmBtn)
    await waitFor(() => expect(apiMock.sendCommand).toHaveBeenCalledWith('dev-2', 'set_pir_led', 'on'))
    expect(await within(card).findByText('Confirmed by device')).toBeInTheDocument()
  })

  it('refuses to switch the gas warning LED off without calling the backend', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(textbox(), 'Turn off the gas LED{Enter}')
    const card = await screen.findByLabelText('Device command')
    expect(within(card).getByText(/cannot be turned off remotely/)).toBeInTheDocument()
    expect(apiMock.listDevices).not.toHaveBeenCalled()
    expect(apiMock.sendCommand).not.toHaveBeenCalled()
  })

  it('"Tell me the current gas reading" is a question for the assistant, not a command', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('Tell me the current gas reading.', 'The latest gas reading is 1510 (raw) [S1].'))
    renderPage()
    await toVoice(user)
    await sayInVoiceMode(user, 'Tell me the current gas reading.')

    await waitFor(() => expect(apiMock.chat).toHaveBeenCalledWith('Tell me the current gas reading.', undefined))
    expect(apiMock.listDevices).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Device command')).not.toBeInTheDocument()
  })
})
