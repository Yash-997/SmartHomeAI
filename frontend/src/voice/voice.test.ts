import { describe, expect, it } from 'vitest'
import { confirmationReply, parseDeviceCommand } from './intent'
import { chunkForSpeech, speakableText } from './speechText'

describe('speakableText', () => {
  it('never reads citation labels, ids or markdown aloud', () => {
    const answer =
      '**Living room** is at 23.4 C [S1] and 51 % humidity [S1, S2]. Living room node (esp32-a1b2c3d4e5f6) is online, last command 1b4e28ba-2fa1-11d2-883f-0016d3cca427 applied. See https://example.com/x'
    const spoken = speakableText(answer)
    expect(spoken).toBe('Living room is at 23.4 C and 51 % humidity. Living room node is online, last command applied. See a link')
    expect(spoken).not.toMatch(/\[S\d/)
    expect(spoken).not.toMatch(/esp32-|1b4e28ba|\*\*/)
  })
})

describe('chunkForSpeech', () => {
  it('splits a long answer into short pieces at sentence ends without losing words', () => {
    const text = Array.from({ length: 12 }, (_, i) => `Sentence number ${i + 1} talks about the readings.`).join(' ')
    const chunks = chunkForSpeech(text, 120)
    expect(chunks.length).toBeGreaterThan(3)
    chunks.forEach((c) => expect(c.length).toBeLessThanOrEqual(120))
    expect(chunks.join(' ')).toBe(text)
  })

  it('breaks a single very long sentence at commas or spaces', () => {
    const text = `${'word, '.repeat(80)}end.`
    const chunks = chunkForSpeech(text, 100)
    chunks.forEach((c) => expect(c.length).toBeLessThanOrEqual(100))
    expect(chunks.join(' ').replace(/\s+/g, ' ')).toBe(text.replace(/\s+/g, ' ').trim())
  })
})

describe('parseDeviceCommand', () => {
  it.each([
    ['Turn on the PIR indicator LED.', 'set_pir_led', 'on'],
    ['turn off the pir indicator led', 'set_pir_led', 'off'],
    ['Set the PIR indicator to automatic mode', 'set_pir_led', 'auto'],
    ['Hey, could you please switch the motion light on', 'set_pir_led', 'on'],
    ['Set the gas LED to auto', 'set_gas_led', 'auto'],
    ['Turn the gas warning light on', 'set_gas_led', 'on'],
  ])('%s', (text, action, value) => {
    expect(parseDeviceCommand(text)).toEqual({ kind: 'command', action, value })
  })

  it.each([
    'Tell me the current gas reading.',
    'What is the current temperature?',
    'Is the PIR LED on?',
    'Why did the motion light turn on last night?',
    'Turn on the lights', // which output? too vague to act on
    'Turn on the PIR and gas LEDs', // two outputs at once
  ])('is not a command: %s', (text) => {
    expect(parseDeviceCommand(text)).toBeNull()
  })

  it('refuses to switch the gas warning LED off', () => {
    expect(parseDeviceCommand('Turn off the gas LED')).toMatchObject({ kind: 'refused', action: 'set_gas_led' })
  })
})

describe('confirmationReply', () => {
  it.each([['Yes', 'yes'], ['yes please', 'yes'], ['go ahead', 'yes'], ['No', 'no'], ['cancel that', 'no'], ['What is the humidity?', null]])(
    '%s',
    (text, expected) => expect(confirmationReply(text)).toBe(expected),
  )
})
