import { Fragment, type ReactNode } from 'react'
import { parseBlocks } from './markdownParse'

/**
 * A small Markdown renderer for assistant answers. It builds React elements (never HTML strings), so text from the
 * model cannot inject markup or scripts. Supported: headings, paragraphs, bullet and numbered lists, pipe tables,
 * fenced code, inline **bold**, *italic*, `code` and citation markers like [S2] (rendered as badges). Links are shown
 * as plain text on purpose: an answer must not be able to send the user somewhere.
 */
const INLINE = /(\*\*[^*]+\*\*|`[^`]+`|\*[^*\s][^*]*\*|_[^_\s][^_]*_|\[S\d+\])/g

export function Inline({ text, cited }: { text: string; cited?: (ref: string) => void }) {
  const parts = text.split(INLINE)
  return (
    <>
      {parts.map((p, i) => {
        if (!p) return null
        if (/^\*\*[^*]+\*\*$/.test(p)) return <strong key={i} className="font-semibold text-slate-50">{p.slice(2, -2)}</strong>
        if (/^`[^`]+`$/.test(p)) return <code key={i} className="rounded bg-slate-900/70 px-1 py-0.5 font-mono text-[0.85em]">{p.slice(1, -1)}</code>
        if (/^\[S\d+\]$/.test(p)) {
          const ref = p.slice(1, -1)
          return (
            <button key={i} type="button" onClick={() => cited?.(ref)} title={`Source ${ref}`}
              className="mx-0.5 inline-flex -translate-y-px items-center rounded bg-indigo-500/20 px-1 align-baseline font-mono text-[0.7em] text-indigo-200 hover:bg-indigo-500/35">
              {ref}
            </button>
          )
        }
        if (/^(\*[^*\s][^*]*\*|_[^_\s][^_]*_)$/.test(p)) return <em key={i}>{p.slice(1, -1)}</em>
        return <Fragment key={i}>{p}</Fragment>
      })}
    </>
  )
}

export default function Markdown({ text, onCite }: { text: string; onCite?: (ref: string) => void }) {
  const blocks = parseBlocks(text)
  const nodes: ReactNode[] = blocks.map((b, i) => {
    switch (b.kind) {
      case 'heading':
        return <p key={i} className={`font-semibold text-slate-50 ${b.level <= 2 ? 'text-base' : 'text-[15px]'}`}><Inline text={b.text} cited={onCite} /></p>
      case 'paragraph':
        return <p key={i}><Inline text={b.text} cited={onCite} /></p>
      case 'code':
        return <pre key={i} className="overflow-x-auto rounded-lg bg-slate-900/80 p-3 font-mono text-xs">{b.text}</pre>
      case 'list': {
        const L = b.ordered ? 'ol' : 'ul'
        return (
          <L key={i} className={`space-y-1 pl-5 ${b.ordered ? 'list-decimal' : 'list-disc'} marker:text-slate-400`}>
            {b.items.map((it, j) => <li key={j}><Inline text={it} cited={onCite} /></li>)}
          </L>
        )
      }
      case 'table':
        return (
          <div key={i} className="overflow-x-auto rounded-lg border border-slate-600/60">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-800/80 text-xs uppercase tracking-wide text-slate-300">
                <tr>{b.header.map((h, j) => <th key={j} className="px-3 py-2 font-medium"><Inline text={h} /></th>)}</tr>
              </thead>
              <tbody>
                {b.rows.map((r, j) => (
                  <tr key={j} className="border-t border-slate-700/60">
                    {r.map((c, k) => <td key={k} className="px-3 py-1.5 tabular-nums"><Inline text={c} cited={onCite} /></td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
    }
  })
  return <div className="space-y-2.5 break-words text-slate-100">{nodes}</div>
}
