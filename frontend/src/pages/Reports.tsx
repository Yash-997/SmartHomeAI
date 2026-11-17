import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, m } from 'motion/react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError, downloadReportPdf, type Device, type ReportDetail, type ReportKind, type ReportSummary } from '../api'
import Shell, { PageHeader } from '../components/Shell'
import { LogoMark } from '../ui/Logo'
import { Skeleton } from '../ui/Skeleton'
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
      await downloadReportPdf(report.id, `veyra-${report.kind.toLowerCase()}-report-${report.createdAt.slice(0, 10)}.pdf`)
      notify('PDF downloaded')
    } catch (e) {
      notify(e instanceof ApiError ? e.message : 'Could not download the PDF')
    } finally {
      setDownloading(false)
    }
  }
  return (
    <article className="card flex flex-col overflow-hidden !p-0 lg:min-h-0" aria-labelledby="report-title">
      {/* The header (title and actions) stays in view; on large screens the report body scrolls on its own below it. */}
      <header className="relative shrink-0 overflow-hidden border-b border-slate-700 bg-linear-to-br from-slate-600/70 via-slate-800 to-slate-900 p-6 sm:p-7">
        <div aria-hidden="true" className="pointer-events-none absolute -right-10 -top-10 opacity-[0.07]"><LogoMark size={220} tile={false} /></div>
        <div className="relative flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="brand-eyebrow">VÉYRA Report</p>
            <h2 id="report-title" className="font-display mt-1.5 text-3xl font-semibold leading-tight">{report.content.title}</h2>
            <div className="mt-3 flex flex-wrap gap-2 text-xs">
              <span className="rounded-full border border-slate-700 bg-white/70 px-2.5 py-1 text-slate-200">{report.content.periodLabel}</span>
              <span className="rounded-full border border-slate-700 bg-white/70 px-2.5 py-1 text-slate-300">{report.content.scope}</span>
              <span className="rounded-full border border-slate-700 bg-white/70 px-2.5 py-1 text-slate-400">Generated {when(report.createdAt)} · {report.timeZone}</span>
            </div>
          </div>
          <div className="flex gap-2">
          <button type="button" onClick={() => void download()} disabled={downloading} className="btn-primary flex items-center gap-2 rounded-xl px-4 py-2 text-sm disabled:opacity-60">
            {downloading ? <span className="spinner" aria-hidden="true" /> : <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14" /></svg>}
            {downloading ? 'Preparing...' : 'Download PDF'}
          </button>
          <button type="button" onClick={onDelete} className="btn-secondary rounded-xl px-4 py-2 text-sm">Delete</button>
          </div>
        </div>
      </header>
      <div data-testid="report-body" className="space-y-6 pt-6 lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:overscroll-contain">
      {report.content.sections.map((s, n) => (
        <section key={s.heading} className="space-y-2 px-6 sm:px-7">
          <h3 className="flex items-center gap-3 text-base font-semibold text-slate-100">
            <span aria-hidden="true" className="grid h-6 w-6 place-items-center rounded-md bg-slate-600 text-[11px] font-bold text-indigo-100">{n + 1}</span>
            {s.heading}
          </h3>
          {s.paragraphs.map((p, i) => <p key={i} className="text-sm leading-relaxed text-slate-200">{p}</p>)}
          {s.bullets.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-sm text-slate-200 marker:text-slate-500">
              {s.bullets.map((b, i) => <li key={i}>{b}</li>)}
            </ul>
          )}
          {s.visuals.length > 0 && <div className="grid gap-3 lg:grid-cols-2">{s.visuals.map((v, i) => <VisualView key={i} visual={v} />)}</div>}
        </section>
      ))}
      <footer className="space-y-1 border-t border-slate-700 px-6 pb-6 pt-4 text-xs text-slate-500 sm:px-7">
        {report.content.notes.map((n) => <p key={n}>{n}</p>)}
      </footer>
      </div>
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
      <PageHeader title="Reports" subtitle="Summaries computed from your stored readings and events. Every number comes from the database." />

      {/* On large screens the two columns fill the rest of the viewport and scroll independently, so the page itself stays put. */}
      <div className="grid gap-6 lg:h-[calc(100dvh-13.5rem)] lg:min-h-[34rem] lg:grid-cols-[18rem_1fr]">
        <aside className="space-y-6 lg:flex lg:min-h-0 lg:flex-col">
          <section className="card shrink-0 space-y-3 p-4" aria-labelledby="new-report">
            <h2 id="new-report" className="font-semibold">New report</h2>
            <div role="radiogroup" aria-label="Report period" className="grid grid-cols-2 gap-2">
              {KINDS.map((k) => (
                <button key={k.kind} type="button" role="radio" aria-checked={kind === k.kind} onClick={() => setKind(k.kind)} title={k.hint}
                  className={`relative rounded-lg border px-3 py-2.5 text-left text-sm font-medium transition-colors ${kind === k.kind ? 'border-transparent text-white' : 'border-slate-700 bg-slate-900/60 text-slate-300 hover:border-slate-600 hover:text-slate-100'}`}>
                  {kind === k.kind && (
                    <m.span layoutId="report-kind" aria-hidden="true" className="absolute inset-0 rounded-lg bg-indigo-400" transition={{ type: 'spring', stiffness: 400, damping: 34 }} />
                  )}
                  <span className="relative block">{k.label}</span>
                </button>
              ))}
            </div>
            <p className="text-xs text-slate-400">{KINDS.find((k) => k.kind === kind)?.hint}</p>
            {(kind === 'custom' || kind === 'daily') && (
              <div className="grid grid-cols-2 gap-2 text-xs text-slate-400">
                <label>{kind === 'daily' ? 'Day' : 'From'}
                  <input type="date" value={from} max={today()} onChange={(e) => setFrom(e.target.value)}
                    className="field-input mt-1 w-full rounded-lg px-2 py-1.5 text-sm text-slate-100" />
                </label>
                {kind === 'custom' && (
                  <label>To
                    <input type="date" value={to} max={today()} onChange={(e) => setTo(e.target.value)}
                      className="field-input mt-1 w-full rounded-lg px-2 py-1.5 text-sm text-slate-100" />
                  </label>
                )}
              </div>
            )}
            <label className="block text-xs text-slate-400">Devices
              <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}
                className="field-input mt-1 w-full rounded-lg px-2 py-1.5 text-sm text-slate-100">
                <option value="">All devices</option>
                {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </label>
            {error && <p className="rounded bg-red-500/10 p-2 text-sm text-red-400" role="alert">{error}</p>}
            <button type="button" onClick={() => void generate()} disabled={busy} className="btn-primary flex w-full items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm disabled:opacity-60">
              {busy && <span className="spinner" aria-hidden="true" />}
              {busy ? 'Generating...' : 'Generate report'}
            </button>
            {busy && (
              <div role="status" className="space-y-1.5">
                <div className="progress-indeterminate" aria-hidden="true" />
                <p className="text-[11px] text-slate-400">Computing statistics from your stored readings and events...</p>
              </div>
            )}
          </section>

          <section className="card flex max-h-[24rem] flex-col p-4 lg:max-h-none lg:min-h-0 lg:flex-1" aria-labelledby="history">
            <h2 id="history" className="shrink-0 font-semibold">History</h2>
            {list === null && !listError && <p className="mt-2 text-sm text-slate-400">Loading...</p>}
            {listError && <p className="mt-2 text-sm text-red-400">{listError} <button type="button" onClick={load} className="underline">Retry</button></p>}
            {list?.length === 0 && <p className="mt-2 text-sm text-slate-500">No reports yet.</p>}
            <ul data-testid="report-history" className="-mx-1 mt-2 min-h-0 flex-1 space-y-1 overflow-y-auto overscroll-contain px-1">
              {list?.map((r) => (
                <li key={r.id}>
                  <button type="button" onClick={() => setParams({ open: r.id }, { replace: true })} aria-current={open?.id === r.id}
                    className={`w-full rounded-lg px-2.5 py-2 text-left text-sm transition-colors focus-visible:outline-offset-[-2px] ${open?.id === r.id ? 'bg-slate-700' : 'hover:bg-slate-700/50'}`}>
                    <span className="block truncate font-medium text-slate-200">{r.title}</span>
                    <span className="block text-xs text-slate-400">{when(r.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        </aside>

        <div className="lg:min-h-0">
          <AnimatePresence mode="wait" initial={false}>
            {opening && (
              <m.div key="opening" role="status" className="card space-y-3 p-6" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                <p className="text-sm text-slate-400">Opening the report...</p>
                <Skeleton className="h-24 rounded-xl" />
                <Skeleton className="h-4 w-3/4 rounded" />
                <Skeleton className="h-4 w-1/2 rounded" />
              </m.div>
            )}
            {!opening && open && (
              <m.div key={open.id} className="lg:flex lg:h-full lg:flex-col" initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.3, ease: [0.2, 0.7, 0.2, 1] }}>
                <ReportPreview report={open} onDelete={() => void remove(open.id)} />
              </m.div>
            )}
            {!opening && !open && (
              <m.div key="empty" className="card flex flex-col items-center gap-4 p-10 text-center text-sm text-slate-400" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                <LogoMark size={52} />
                <p className="max-w-md">Generate a report, or pick one from the history, to preview it here. You can also ask the assistant: “Generate a weekly report”.</p>
              </m.div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </Shell>
  )
}
