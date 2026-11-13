import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  title: string
  children: ReactNode
}

/**
 * Keeps one broken panel from blanking the whole page: if a panel throws while rendering, only that panel is replaced
 * by a short message (and the error goes to the browser console).
 */
export default class PanelBoundary extends Component<Props, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`${this.props.title} panel failed to render`, error, info.componentStack)
  }

  render() {
    if (this.state.failed) {
      return (
        <section className="rounded-xl bg-slate-800 p-5 text-sm shadow-lg" role="alert">
          <h3 className="font-semibold">{this.props.title}</h3>
          <p className="mt-1 text-red-300">This panel could not be displayed. Reload the page; if it keeps happening, the backend may be an older version than the web app.</p>
          <button type="button" onClick={() => this.setState({ failed: false })} className="mt-2 text-xs text-indigo-300 underline">Try again</button>
        </section>
      )
    }
    return this.props.children
  }
}
