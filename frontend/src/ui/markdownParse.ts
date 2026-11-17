/** Block-level Markdown parsing for assistant answers (see Markdown.tsx). Pure: no HTML is produced. */
export type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'table'; header: string[]; rows: string[][] }
  | { kind: 'code'; text: string }

const isTableRow = (l: string) => /^\s*\|.*\|\s*$/.test(l)
const isDivider = (l: string) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l)
const cells = (l: string) => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())

export function parseBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  const out: Block[] = []
  let para: string[] = []
  const flush = () => {
    if (para.length) out.push({ kind: 'paragraph', text: para.join(' ') })
    para = []
  }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^\s*```/.test(line)) {
      flush()
      const code: string[] = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++])
      out.push({ kind: 'code', text: code.join('\n') })
      continue
    }
    const h = /^\s*(#{1,4})\s+(.*)$/.exec(line)
    if (h) {
      flush()
      out.push({ kind: 'heading', level: h[1].length, text: h[2].trim() })
      continue
    }
    if (isTableRow(line) && i + 1 < lines.length && isDivider(lines[i + 1])) {
      flush()
      const header = cells(line)
      const rows: string[][] = []
      i += 2
      while (i < lines.length && isTableRow(lines[i])) rows.push(cells(lines[i++]))
      i--
      out.push({ kind: 'table', header, rows })
      continue
    }
    const bullet = /^\s*[-*â€¢]\s+(.*)$/.exec(line)
    const numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line)
    if (bullet || numbered) {
      flush()
      const ordered = !!numbered
      const items: string[] = []
      while (i < lines.length) {
        const m = ordered ? /^\s*\d+[.)]\s+(.*)$/.exec(lines[i]) : /^\s*[-*â€¢]\s+(.*)$/.exec(lines[i])
        if (m) items.push(m[1])
        else if (/^\s{2,}\S/.test(lines[i]) && items.length) items[items.length - 1] += ' ' + lines[i].trim()
        else break
        i++
      }
      i--
      out.push({ kind: 'list', ordered, items })
      continue
    }
    if (line.trim() === '') flush()
    else para.push(line.trim())
  }
  flush()
  return out
}
