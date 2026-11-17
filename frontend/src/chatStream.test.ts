import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, chatStream, parseSse } from './api'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('chatStream', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('reports a failed stream request instead of asking the question a second time', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(429, { message: 'Too many questions' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(chatStream('hi', undefined, () => {})).rejects.toMatchObject({ status: 429, message: 'Too many questions' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('a proxy timeout is reported, not retried', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('<html>504</html>', { status: 504 }))
    vi.stubGlobal('fetch', fetchMock)
    const err = await chatStream('hi', undefined, () => {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(504)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('falls back to the plain endpoint only when streaming does not exist', async () => {
    const reply = { conversationId: 'c', conversationTitle: 't', userMessage: {}, assistantMessage: {} }
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response('', { status: 404 })).mockResolvedValueOnce(jsonResponse(200, reply))
    vi.stubGlobal('fetch', fetchMock)
    await expect(chatStream('hi', undefined, () => {})).resolves.toMatchObject({ conversationId: 'c' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[1][0])).toBe('/api/assistant/chat')
  })

  it('parses progress and done events', async () => {
    const body = 'event:progress\ndata:{"stage":"querying","detail":"x"}\n\nevent:done\ndata:{"conversationId":"c"}\n\n'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })))
    const progress: unknown[] = []
    await expect(chatStream('hi', undefined, (p) => progress.push(p))).resolves.toMatchObject({ conversationId: 'c' })
    expect(progress).toEqual([{ stage: 'querying', detail: 'x' }])
    expect(parseSse('event:x')).toBeNull()
  })
})
