import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError, downloadReportPdf, type Device, type ReportDetail, type ReportKind, type ReportSummary } from '../api'
import Shell from '../components/Shell'
import VisualView from '../components/VisualView'
import { notify } from '../ui/toastStore'

const KINDS: { kind: ReportKind; label: string; hint: string }[] = [
  { kind: 'daily', label: 'Daily', hint: 'Today so far, or one chosen day' },
  { kind: 'weekly', label: 'Weekly', hint: 'The last 7 days' },
  { kind: 'monthly', label: 'Monthly', hint: 'The last 30 days' },
  { kind: 'custom', label: 'Custom', hint: 'Any range up to 366 days' },
]

const when = (iso: string) => new Date(iso).toLocaleString([], { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const today = () => new Date().toLocaleDateString('en-CA')

function ReportPreview({ report, onDelete }: { report: ReportDetail; onDelete: () => void }) {
  const [downloading, setDownloading] = useState(false)
  const download = async () => {
    setDownloading(true)
    try {
      await downloadReportPdf(report.id, `smarthome-${report.kind.toLowerCase()}-report-${report.createdAt.slice(0, 10)}.pdf`)
    } catch (e) {
      notify(e instanceof ApiError ? e.message : 'Could not download the PDF')
    } finally {
      setDownloading(false)
    }
  }
  return (
    <article className="card space-y-5 p-5" aria-labelledby="report-title">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="report-title" className="text-xl font-semibold">{report.content.title}</h2>
          <p className="mt-1 text-sm text-slate-400">
            {report.content.scope} · generated {when(report.createdAt)} · times in {report.timeZone}
          </p>
        </div>
        <div className="flex gap-2">
          <button type="button" onClick={() => void download()} disabled={downloading} className="btn-primary rounded-xl px-4 py-2 text-sm disabled:opacity-60">
            {downloading ? 'Preparing...' : 'Download PDF'}
          </button>
          <button type="button" onClick={onDelete} className="btn-secondary rounded-xl px-4 py-2 text-sm">Delete</button>
        </div>
      </header>
      {report.content.sections.map((s) => (
        <section key={s.heading} className="space-y-2">
          <h3 className="text-base font-semibold text-indigo-200">{s.heading}</h3>
          {s.paragraphs.map((p, i) => <p key={i} className="text-sm leading-relaxed text-slate-200">{p}</p>)}
          {s.bullets.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-sm text-slate-200 marker:text-slate-500">
              {s.bullets.map((b, i) => <li key={i}>{b}</li>)}
            </ul>
          )}
          {s.visuals.length > 0 && <div className="grid gap-3 lg:grid-cols-2">{s.visuals.map((v, i) => <VisualView key={i} visual={v} />)}</div>}
        </section>
      ))}
      <footer className="space-y-1 border-t border-slate-700/60 pt-3 text-xs text-slate-500">
        {report.content.notes.map((n) => <p key={n}>{n}</p>)}
      </footer>
    </article>
  )
}

export default function Reports() {
  const [params, setParams] = useSearchParams()
  const [list, setList] = useState<ReportSummary[] | null>(null)
  const [listError, setListError] = useState('')
  const [devices, setDevices] = useState<Device[]>([])
  const [kind, setKind] = useState<ReportKind>('weekly')
  const [from, setFrom] = useState(today())
  const [to, setTo] = useState(today())
  const [deviceId, setDeviceId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState<ReportDetail | null>(null)
  const [opening, setOpening] = useState(false)

  const load = useCallback(() => {
    api.reports().then((r) => { setList(r); setListError('') }).catch((e) => setListError(e instanceof ApiError ? e.message : 'Could not load reports'))
  }, [])

  const show = useCallback((id: string) => {
    setOpening(true)
    api.report(id)
      .then(setOpen)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not open the report'))
      .finally(() => setOpening(false))
  }, [])

  useEffect(() => {
    load()
    api.listDevices().then(setDevices).catch(() => setDevices([]))
  }, [load])

  // The URL (?open=id) says which report is shown; a report we already hold is not fetched again.
  const wanted = params.get('open')
  const shownId = useRef<string | null>(null)
  useEffect(() => {
    shownId.current = open?.id ?? null
  }, [open])
  useEffect(() => {
    if (wanted && wanted !== shownId.current) show(wanted)
  }, [wanted, show])

  const generate = async () => {
    setBusy(true)
    setError('')
    try {
      const r = await api.createReport({
        kind,
        from: kind === 'custom' || kind === 'daily' ? from : undefined,
        to: kind === 'custom' ? to : undefined,
        deviceId: deviceId || undefined,
      })
      setOpen(r)
      shownId.current = r.id
      setParams({ open: r.id }, { replace: true })
      load()
      notify('Report created')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not create the report')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (id: string) => {
    if (!window.confirm('Delete this report?')) return
    try {
      await api.deleteReport(id)
      if (open?.id === id) {
        setOpen(null)
        setParams({}, { replace: true })
      }
      load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete the report')
    }
  }

  return (
    <Shell wide>
      <div>
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Reports</h1>
        <p className="mt-1.5 text-sm text-slate-400">Summaries computed from your stored readings and events. Every number comes from the database.</p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[18rem_1fr]">
        <aside className="space-y-6">
          <section className="card space-y-3 p-4" aria-labelledby="new-report">
            <h2 id="new-report" className="font-semibold">New report</h2>
            <div role="radiogroup" aria-label="Report period" className="grid grid-cols-2 gap-2">
              {KINDS.map((k) => (
                <button key={k.kind} type="button" role="radio" aria-checked={kind === k.kind} onClick={() => setKind(k.kind)} title={k.hint}
                  className={`rounded-lg px-3 py-2 text-sm font-medium transition-colors ${kind === k.kind ? 'bg-indigo-400 text-slate-950' : 'bg-slate-800 text-slate-300 hover:text-slate-100'}`}>
                  {k.label}
                </button>
              ))}
            </div>
            <p className="text-xs text-slate-400">{KINDS.find((k) => k.kind === kind)?.hint}</p>
            {(kind === 'custom' || kind === 'daily') && (
              <div className="grid grid-cols-2 gap-2 text-xs text-slate-400">
                <label>{kind === 'daily' ? 'Day' : 'From'}
                  <input type="date" value={from} max={today()} onChange={(e) => setFrom(e.target.value)}
                    className="mt-1 w-full rounded-lg border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm text-slate-100" />
                </label>
                {kind === 'custom' && (
                  <label>To
                    <input type="date" value={to} max={today()} onChange={(e) => setTo(e.target.value)}
                      className="mt-1 w-full rounded-lg border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm text-slate-100" />
                  </label>
                )}
              </div>
            )}
            <label className="block text-xs text-slate-400">Devices
              <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-600 bg-slate-900 px-2 py-1.5 text-sm text-slate-100">
                <option value="">All devices</option>
                {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </label>
            {error && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}
            <button type="button" onClick={() => void generate()} disabled={busy} className="btn-primary w-full rounded-xl px-4 py-2.5 text-sm disabled:opacity-60">
              {busy ? 'Generating...' : 'Generate report'}
            </button>
          </section>

          <section className="card p-4" aria-labelledby="history">
            <h2 id="history" className="font-semibold">History</h2>
            {list === null && !listError && <p className="mt-2 text-sm text-slate-400">Loading...</p>}
            {listError && <p className="mt-2 text-sm text-red-400">{listError} <button type="button" onClick={load} className="underline">Retry</button></p>}
            {list?.length === 0 && <p className="mt-2 text-sm text-slate-500">No reports yet.</p>}
            <ul className="mt-2 space-y-1">
              {list?.map((r) => (
                <li key={r.id}>
                  <button type="button" onClick={() => setParams({ open: r.id }, { replace: true })} aria-current={open?.id === r.id}
                    className={`w-full rounded-lg px-2.5 py-2 text-left text-sm transition-colors ${open?.id === r.id ? 'bg-slate-700' : 'hover:bg-slate-700/50'}`}>
                    <span className="block truncate font-medium text-slate-200">{r.title}</span>
                    <span className="block text-xs text-slate-400">{when(r.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        </aside>

        <div>
          {opening && <p className="text-sm text-slate-400" role="status">Opening the report...</p>}
          {!opening && open && <ReportPreview report={open} onDelete={() => void remove(open.id)} />}
          {!opening && !open && (
            <div className="card p-8 text-center text-sm text-slate-400">
              Generate a report, or pick one from the history, to preview it here. You can also ask the assistant: “Generate a weekly report”.
            </div>
          )}
        </div>
      </div>
    </Shell>
  )
}
