import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { parseSse } from '../api'
import Markdown from './Markdown'
import { parseBlocks } from './markdownParse'

describe('parseBlocks', () => {
  it('recognises headings, lists, tables and code', () => {
    const b = parseBlocks('## Today\n\n- one\n- two\n\n1. first\n2. second\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```\nx\n```\nplain text')
    expect(b.map((x) => x.kind)).toEqual(['heading', 'list', 'list', 'table', 'code', 'paragraph'])
    expect(b[3]).toEqual({ kind: 'table', header: ['A', 'B'], rows: [['1', '2']] })
  })
})

describe('Markdown', () => {
  it('renders formatting and citation badges', () => {
    const onCite = vi.fn()
    render(<Markdown text={'It was **23.4 °C** on average [S2].'} onCite={onCite} />)
    expect(screen.getByText('23.4 °C').tagName).toBe('STRONG')
    screen.getByRole('button', { name: 'S2' }).click()
    expect(onCite).toHaveBeenCalledWith('S2')
  })

  it('never turns model text into HTML or links', () => {
    const { container } = render(<Markdown text={'<img src=x onerror=alert(1)> [click](javascript:alert(1)) <script>bad()</script>'} />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('a')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('renders tables with headers', () => {
    render(<Markdown text={'| Sensor | Average |\n|---|---|\n| Temperature | 22.1 |'} />)
    expect(screen.getByRole('columnheader', { name: 'Sensor' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '22.1' })).toBeInTheDocument()
  })
})

describe('parseSse', () => {
  it('reads named JSON events and ignores broken ones', () => {
    expect(parseSse('event:progress\ndata:{"stage":"querying","detail":"x"}')).toEqual({ name: 'progress', data: { stage: 'querying', detail: 'x' } })
    expect(parseSse('event:done\ndata: {"a":1}')).toEqual({ name: 'done', data: { a: 1 } })
    expect(parseSse('event:done\ndata:{not json')).toBeNull()
    expect(parseSse(': comment')).toBeNull()
  })
})
