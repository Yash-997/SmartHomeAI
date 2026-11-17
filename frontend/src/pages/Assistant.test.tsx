// Component tests for the text assistant. The backend is mocked (api module) and so is the browser speech-synthesis
// API (see test/speechMocks.ts): these prove what the page does with them, not that a real voice, language model or
// device works.
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssistantStatus, ChatMessage, ChatReply, Command, Device } from '../api'
import { installSynthesis, removeSynthesis } from '../test/speechMocks'
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
/** The rendered answer: [S1] becomes a citation badge, so the text spans elements. */
const rendered = (text: string) => (_: string, el: Element | null) => el?.tagName === 'P' && el.textContent === text
const answerText = rendered('It is 24.6 C S1.')

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

let synth: ReturnType<typeof installSynthesis>

beforeEach(() => {
  n = 0
  Object.values(apiMock).forEach((f) => f.mockReset())
  apiMock.assistantStatus.mockResolvedValue(STATUS)
  apiMock.conversations.mockResolvedValue([])
  apiMock.knowledgeDocs.mockResolvedValue([])
  apiMock.conversationMessages.mockResolvedValue([])
  synth = installSynthesis()
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
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

  it('2. shows the assistant answer with its cited sources, and does not speak it unless asked', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('What is the current temperature?', 'It is 24.6 C [S1].'))
    renderPage()

    await user.type(textbox(), 'What is the current temperature?{Enter}')

    expect(await screen.findByText(answerText)).toBeInTheDocument()
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
})

describe('voice chat is removed', () => {
  it('has no Text/Voice toggle, no microphone button and no voice panel', async () => {
    renderPage()
    expect(await screen.findByText('What would you like to know?')).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup', { name: 'Input mode' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /voice|text/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /mic|microphone|dictat|listening/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Voice assistant')).not.toBeInTheDocument()
    expect(screen.queryByText(/by voice/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /mute/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument() // no voice selector
    expect(screen.queryByText(/browser default/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ask' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /New conversation/ })).toBeInTheDocument()
  })

  it('ignores a stale "voice" mode remembered from an earlier version', async () => {
    localStorage.setItem('smarthome.assistant.mode', 'voice')
    try {
      renderPage()
      expect(await screen.findByText('What would you like to know?')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /mic|microphone/i })).not.toBeInTheDocument()
    } finally {
      localStorage.removeItem('smarthome.assistant.mode')
    }
  })
})

describe('read aloud', () => {
  async function askTyped(user: ReturnType<typeof userEvent.setup>, answer: string) {
    apiMock.chat.mockResolvedValue(reply('what is the temperature', answer))
    renderPage()
    await user.type(textbox(), 'what is the temperature{Enter}')
    await screen.findByRole('button', { name: 'Read this answer aloud' })
  }

  it('reads an answer aloud on request, without citation labels, and splits long answers', async () => {
    const user = userEvent.setup()
    const long = Array.from({ length: 10 }, (_, i) => `Reading ${i + 1} from the living room sensor was within the normal range [S${i + 1}].`).join(' ')
    await askTyped(user, long)
    expect(synth.speak).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Read this answer aloud' }))
    expect(synth.speak.mock.calls.length).toBeGreaterThan(1)
    expect(synth.spokenText()).not.toMatch(/\[S\d+\]/)
    expect(synth.spokenText()).toContain('Reading 10 from the living room sensor was within the normal range.')
    expect(screen.getByRole('button', { name: 'Stop speaking' })).toBeInTheDocument()
  })

  it('stops speaking immediately', async () => {
    const user = userEvent.setup()
    await askTyped(user, 'It is 24.6 C [S1].')
    await user.click(screen.getByRole('button', { name: 'Read this answer aloud' }))
    synth.cancel.mockClear()

    await user.click(screen.getByRole('button', { name: 'Stop speaking' }))
    expect(synth.cancel).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Stop speaking' })).not.toBeInTheDocument()
  })

  it('ignores mute and voice preferences left over from the removed voice mode', async () => {
    localStorage.setItem('smarthome.voice.muted', 'true')
    localStorage.setItem('smarthome.voice.voiceURI', 'gone-voice')
    try {
      const user = userEvent.setup()
      await askTyped(user, 'It is 24.6 C [S1].')
      await user.click(screen.getByRole('button', { name: 'Read this answer aloud' }))
      expect(synth.spokenText()).toBe('It is 24.6 C.')
    } finally {
      localStorage.removeItem('smarthome.voice.muted')
      localStorage.removeItem('smarthome.voice.voiceURI')
    }
  })

  it('hides Read aloud when the browser cannot speak; the answer is still shown as text', async () => {
    removeSynthesis()
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(reply('what is the temperature', 'It is 24.6 C [S1].'))
    renderPage()
    await user.type(textbox(), 'what is the temperature{Enter}')
    expect(await screen.findByText(answerText)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Read this answer aloud' })).not.toBeInTheDocument()
  })
})

describe('device commands', () => {
  const typeIn = async (user: ReturnType<typeof userEvent.setup>, text: string) => user.type(textbox(), `${text}{Enter}`)

  it('asks for confirmation, sends through the command API only after "yes", and reports success only after the device acknowledges', async () => {
    const user = userEvent.setup()
    apiMock.listDevices.mockResolvedValue([device()])
    apiMock.sendCommand.mockResolvedValue(command('SENT'))
    apiMock.getCommand.mockResolvedValueOnce(command('SENT')).mockResolvedValue(command('ACKNOWLEDGED'))
    renderPage()

    await typeIn(user, 'Turn on the PIR indicator LED')
    const card = await screen.findByLabelText('Device command')
    expect(within(card).getByText('Turn the PIR indicator LED on')).toBeInTheDocument()
    expect(within(card).getByText(/Device: Living room node/)).toBeInTheDocument()
    expect(apiMock.sendCommand).not.toHaveBeenCalled()
    expect(apiMock.chat).not.toHaveBeenCalled() // a command never goes to the language model
    expect(synth.speak).not.toHaveBeenCalled()

    await typeIn(user, 'yes')
    await waitFor(() => expect(apiMock.sendCommand).toHaveBeenCalledWith('dev-1', 'set_pir_led', 'on'))
    expect(await within(card).findByText('Sent, waiting for the device to confirm...')).toBeInTheDocument()
    expect(within(card).queryByText(/^Done/)).not.toBeInTheDocument()

    expect(await within(card).findByText('Confirmed by device', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(within(card).getByText(/Done\. Living room node confirmed the change/)).toBeInTheDocument()
    expect(apiMock.getCommand).toHaveBeenCalledWith('dev-1', 'cmd-1')
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
    await typeIn(user, 'Set the PIR indicator to automatic mode')
    const card = await screen.findByLabelText('Device command')
    expect(within(card).getByText('Set the PIR indicator LED to automatic')).toBeInTheDocument()

    await typeIn(user, 'no')
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
    await typeIn(user, 'Tell me the current gas reading.')

    await waitFor(() => expect(apiMock.chat).toHaveBeenCalledWith('Tell me the current gas reading.', undefined))
    expect(apiMock.listDevices).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Device command')).not.toBeInTheDocument()
  })
})
