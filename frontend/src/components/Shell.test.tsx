// The app frame must offer every section on desktop, keep the phone bottom bar usable, and mark the current page.
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import Shell from './Shell'

vi.mock('../auth', () => ({ useAuth: () => ({ user: { username: 'tester', email: 'tester@example.com' }, logout: vi.fn() }) }))

const SECTIONS = ['Dashboard', 'Devices', 'Automation', 'Security', 'ML Insights', 'Assistant', 'System Diagnostics']

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><Shell><p>page body</p></Shell></MemoryRouter>)
}

describe('Shell', () => {
  it('links to every section from the sidebar', () => {
    renderAt('/')
    const [sidebar] = screen.getAllByRole('navigation', { name: 'Main' })
    for (const name of SECTIONS) expect(within(sidebar).getByRole('link', { name })).toBeInTheDocument()
    expect(screen.getByText('page body')).toBeInTheDocument()
  })

  it('marks the current section for assistive technology', () => {
    renderAt('/security')
    const [sidebar] = screen.getAllByRole('navigation', { name: 'Main' })
    expect(within(sidebar).getByRole('link', { name: 'Security' })).toHaveAttribute('aria-current', 'page')
    expect(within(sidebar).getByRole('link', { name: 'Devices' })).not.toHaveAttribute('aria-current')
  })

  it('keeps the rest of the sections under More on a phone, and closes with Escape', async () => {
    const user = userEvent.setup()
    renderAt('/')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More' }))
    const dialog = screen.getByRole('dialog', { name: 'More' })
    for (const name of ['Automation', 'ML Insights', 'System Diagnostics']) {
      expect(within(dialog).getByRole('link', { name })).toBeInTheDocument()
    }
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('offers a way to skip the navigation', () => {
    renderAt('/')
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#content')
  })
})
