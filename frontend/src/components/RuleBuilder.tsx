import { useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { api, ApiError, type Device, type Rule } from '../api'
import {
  ACTION_LABEL,
  DAYS,
  MOTION_OPERATORS,
  NUMERIC_OPERATORS,
  SENSORS,
  buildRequest,
  draftFromRule,
  emptyDraft,
  timeZones,
  validateDraft,
  type ConditionDraft,
  type Draft,
  type FieldErrors,
} from '../automation'

const input = 'w-full rounded-md border bg-slate-900 px-2 py-1.5 text-sm outline-none focus:ring-2 focus:ring-indigo-500'
const border = (bad: boolean) => (bad ? 'border-red-500' : 'border-slate-600')

function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="text-slate-300">{label}</span>
      <div className="mt-1">{children}</div>
      {hint && !error && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
      {error && <span className="mt-1 block text-xs text-red-400">{error}</span>}
    </label>
  )
}

interface Props {
  devices: Device[]
  /** Existing rule to edit, or null to create a new one. */
  rule: Rule | null
  onClose: () => void
  onSaved: () => void
}

export default function RuleBuilder({ devices, rule, onClose, onSaved }: Props) {
  const [draft, setDraft] = useState<Draft>(() => (rule ? draftFromRule(rule) : emptyDraft(devices[0]?.id ?? '')))
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState('')
  const [busy, setBusy] = useState(false)
  const zones = useMemo(() => timeZones(), [])

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => ({ ...d, [key]: value }))
  const setCondition = (i: number, patch: Partial<ConditionDraft>) =>
    setDraft((d) => ({ ...d, conditions: d.conditions.map((c, idx) => (idx === i ? { ...c, ...patch } : c)) }))

  // The outputs the chosen target device actually supports (the server advertises them; "off" is never offered for gas).
  const target = devices.find((d) => d.id === (draft.targetDeviceId || draft.deviceId))
  const outputs = target?.outputs ?? []
  const values = outputs.find((o) => o.action === draft.action)?.values ?? []

  const changeAction = (action: string) => {
    const vals = outputs.find((o) => o.action === action)?.values ?? []
    setDraft((d) => ({ ...d, action, actionValue: vals.includes(d.actionValue) ? d.actionValue : (vals[0] ?? '') }))
  }

  const changeSensor = (i: number, sensor: ConditionDraft['sensor']) => {
    const motion = sensor === 'motion'
    setCondition(i, { sensor, operator: motion ? '==' : '>', value: motion ? 'true' : '' })
  }

  const toggleDay = (day: string) =>
    set('days', draft.days.includes(day) ? draft.days.filter((d) => d !== day) : [...draft.days, day])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const found = validateDraft(draft)
    setErrors(found)
    setFormError('')
    if (Object.keys(found).length) return
    setBusy(true)
    try {
      const body = buildRequest(draft)
      if (rule) await api.updateRule(rule.id, body)
      else await api.createRule(body)
      onSaved()
    } catch (err) {
      if (err instanceof ApiError) {
        setErrors(err.fieldErrors)
        setFormError(Object.keys(err.fieldErrors).length ? 'Please fix the highlighted fields.' : err.message)
      } else {
        setFormError('Something went wrong. Please try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-10 overflow-y-auto bg-black/60 p-4" role="dialog" aria-modal="true" aria-label="Automation rule">
      <form onSubmit={submit} noValidate className="mx-auto my-4 w-full max-w-2xl space-y-5 rounded-xl bg-slate-800 p-6 shadow-lg">
        <h2 className="text-lg font-semibold">{rule ? 'Edit rule' : 'New automation rule'}</h2>
        {formError && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400">{formError}</p>}

        <Field label="Name" error={errors.name}>
          <input className={`${input} ${border(!!errors.name)}`} value={draft.name} maxLength={60}
            onChange={(e) => set('name', e.target.value)} placeholder="e.g. Warm room indicator" />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Watch device (sensors)" error={errors.deviceId}>
            <select className={`${input} ${border(!!errors.deviceId)}`} value={draft.deviceId} onChange={(e) => set('deviceId', e.target.value)}>
              {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Act on device" hint="Defaults to the same device">
            <select className={`${input} ${border(false)}`} value={draft.targetDeviceId} onChange={(e) => set('targetDeviceId', e.target.value)}>
              <option value="">Same device</option>
              {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
        </div>

        {/* ---- when ---- */}
        <fieldset className="space-y-3 rounded-lg border border-slate-700 p-4">
          <legend className="px-1 text-sm font-medium">When</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input type="radio" name="trigger" checked={draft.trigger === 'TELEMETRY'} onChange={() => set('trigger', 'TELEMETRY')} />
              New sensor readings arrive
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="trigger" checked={draft.trigger === 'SCHEDULE'} onChange={() => set('trigger', 'SCHEDULE')} />
              At a scheduled time
            </label>
          </div>

          {draft.trigger === 'SCHEDULE' && (
            <div className="space-y-3">
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Time (24 hour)" error={errors['trigger.time']}>
                  <input type="time" className={`${input} ${border(!!errors['trigger.time'])}`} value={draft.time} onChange={(e) => set('time', e.target.value)} />
                </Field>
                <Field label="Time zone" error={errors['trigger.timeZone']} hint="The time is read in this zone, including daylight saving">
                  <select className={`${input} ${border(!!errors['trigger.timeZone'])}`} value={draft.timeZone} onChange={(e) => set('timeZone', e.target.value)}>
                    {zones.map((z) => <option key={z} value={z}>{z}</option>)}
                  </select>
                </Field>
              </div>
              <div>
                <span className="text-sm text-slate-300">Days</span>
                <div className="mt-1 flex flex-wrap gap-2">
                  {DAYS.map((day) => (
                    <button key={day} type="button" onClick={() => toggleDay(day)} aria-pressed={draft.days.includes(day)}
                      className={`rounded-md px-3 py-1 text-sm ${draft.days.includes(day) ? 'bg-indigo-600' : 'bg-slate-700 hover:bg-slate-600'}`}>
                      {day}
                    </button>
                  ))}
                </div>
                {errors['trigger.days'] && <span className="mt-1 block text-xs text-red-400">{errors['trigger.days']}</span>}
              </div>
            </div>
          )}

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm text-slate-300">
                {draft.trigger === 'SCHEDULE' ? 'Only if (optional, uses the latest fresh readings)' : 'Conditions'}
              </span>
              {draft.conditions.length > 1 && (
                <div className="flex overflow-hidden rounded-md text-xs" role="group" aria-label="Combine conditions with">
                  {(['AND', 'OR'] as const).map((l) => (
                    <button key={l} type="button" onClick={() => set('logic', l)} aria-pressed={draft.logic === l}
                      className={`px-3 py-1 ${draft.logic === l ? 'bg-indigo-600' : 'bg-slate-700 hover:bg-slate-600'}`}>
                      {l === 'AND' ? 'All of these' : 'Any of these'}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {errors.conditions && <p className="text-xs text-red-400">{errors.conditions}</p>}

            {draft.conditions.map((c, i) => {
              const sensor = SENSORS.find((s) => s.id === c.sensor)!
              const ops = c.sensor === 'motion' ? MOTION_OPERATORS : NUMERIC_OPERATORS
              const err = errors[`conditions[${i}].value`]
              return (
                <div key={i} className="space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <select aria-label={`Condition ${i + 1} sensor`} className={`${input} w-auto ${border(!!errors[`conditions[${i}].sensor`])}`}
                      value={c.sensor} onChange={(e) => changeSensor(i, e.target.value as ConditionDraft['sensor'])}>
                      {SENSORS.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
                    </select>
                    <select aria-label={`Condition ${i + 1} operator`} className={`${input} w-auto ${border(!!errors[`conditions[${i}].operator`])}`}
                      value={c.operator} onChange={(e) => setCondition(i, { operator: e.target.value })}>
                      {ops.map((o) => <option key={o} value={o}>{o}</option>)}
                    </select>
                    {c.sensor === 'motion' ? (
                      <select aria-label={`Condition ${i + 1} value`} className={`${input} w-auto ${border(!!err)}`} value={c.value}
                        onChange={(e) => setCondition(i, { value: e.target.value })}>
                        <option value="true">detected</option>
                        <option value="false">not detected</option>
                      </select>
                    ) : (
                      <input aria-label={`Condition ${i + 1} value`} inputMode="decimal" className={`${input} w-28 ${border(!!err)}`} value={c.value}
                        onChange={(e) => setCondition(i, { value: e.target.value })} placeholder={sensor.unit || 'value'} />
                    )}
                    {sensor.unit && <span className="text-xs text-slate-500">{sensor.unit}</span>}
                    <button type="button" onClick={() => set('conditions', draft.conditions.filter((_, idx) => idx !== i))}
                      className="ml-auto rounded-md bg-slate-700 px-2 py-1 text-xs hover:bg-slate-600" aria-label={`Remove condition ${i + 1}`}>Remove</button>
                  </div>
                  {err ? <p className="text-xs text-red-400">{err}</p> : errors[`conditions[${i}].operator`] ? (
                    <p className="text-xs text-red-400">{errors[`conditions[${i}].operator`]}</p>
                  ) : (
                    <p className="text-xs text-slate-500">{sensor.hint}</p>
                  )}
                </div>
              )
            })}
            <button type="button" disabled={draft.conditions.length >= 10}
              onClick={() => set('conditions', [...draft.conditions, { sensor: 'temperature', operator: '>', value: '' }])}
              className="rounded-md bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600 disabled:opacity-40">
              Add condition
            </button>
            <p className="text-xs text-slate-500">A missing or stale reading never counts as zero: the rule simply waits.</p>
          </div>
        </fieldset>

        {/* ---- then ---- */}
        <fieldset className="space-y-3 rounded-lg border border-slate-700 p-4">
          <legend className="px-1 text-sm font-medium">Then</legend>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Output" error={errors['action.action']}>
              <select className={`${input} ${border(!!errors['action.action'])}`} value={draft.action} onChange={(e) => changeAction(e.target.value)}>
                {(outputs.length ? outputs : [{ action: draft.action, label: ACTION_LABEL[draft.action] ?? draft.action, values: [] }]).map((o) => (
                  <option key={o.action} value={o.action}>{o.label}</option>
                ))}
              </select>
            </Field>
            <Field label="Set to" error={errors['action.value']}>
              <select className={`${input} ${border(!!errors['action.value'])}`} value={draft.actionValue} onChange={(e) => set('actionValue', e.target.value)}>
                {(values.length ? values : [draft.actionValue]).map((v) => <option key={v} value={v}>{v}</option>)}
              </select>
            </Field>
          </div>
          <p className="text-xs text-slate-500">
            The gas warning LED can be forced on or returned to auto, but never turned off: it always lights during a gas warning.
          </p>
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Priority (0-100)" error={errors.priority} hint="Higher wins when rules compete for the same output">
            <input inputMode="numeric" className={`${input} ${border(!!errors.priority)}`} value={draft.priority} onChange={(e) => set('priority', e.target.value)} />
          </Field>
          <Field label="Cooldown (seconds)" error={errors.cooldownSeconds} hint="Minimum time between runs of this rule (5 to 86400)">
            <input inputMode="numeric" className={`${input} ${border(!!errors.cooldownSeconds)}`} value={draft.cooldown} onChange={(e) => set('cooldown', e.target.value)} />
          </Field>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={draft.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          Rule is enabled
        </label>

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-md bg-slate-700 px-4 py-2 text-sm hover:bg-slate-600">Cancel</button>
          <button disabled={busy} className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60">
            {busy ? 'Saving...' : rule ? 'Save changes' : 'Create rule'}
          </button>
        </div>
      </form>
    </div>
  )
}
