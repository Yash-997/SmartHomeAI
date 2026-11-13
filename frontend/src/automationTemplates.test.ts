import { describe, expect, it } from 'vitest'
import { RULE_TEMPLATES, buildRequest, emptyDraft, validateDraft } from './automation'

describe('rule templates', () => {
  it.each(RULE_TEMPLATES.map((t) => [t.title, t] as const))('%s is a valid rule the server would accept', (_title, t) => {
    const draft = { ...emptyDraft('device-1'), ...t.draft }
    expect(validateDraft(draft)).toEqual({})
    const body = buildRequest(draft)
    expect(body.name).toBe(t.draft.name)
  })

  it('never offers to turn the gas LED off', () => {
    for (const t of RULE_TEMPLATES.filter((x) => x.draft.action === 'set_gas_led')) expect(t.draft.actionValue).not.toBe('off')
  })
})
