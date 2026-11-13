import { connect } from 'node:net'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv, type ProxyOptions } from 'vite'

/** True if something accepts TCP connections on localhost:port. */
function listening(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = connect({ port, host: 'localhost' })
    const done = (ok: boolean) => {
      socket.destroy()
      resolve(ok)
    }
    socket.setTimeout(500, () => done(false))
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
  })
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '') // the project-root .env
  // An explicit VITE_API_TARGET always wins. Otherwise use the first backend that is actually running: one started
  // from the IDE/Maven (SERVER_PORT, default 8080) or the Docker Compose one (published on BACKEND_HOST_PORT, default 8082).
  //
  // The choice is re-checked per request (cached briefly), NOT once at startup: starting `npm run dev` before the
  // backend is up, or switching between the IDE backend and Docker, must not leave the proxy pointing at a dead port
  // (that used to make every /api call, including login, fail with 502 until Vite was restarted).
  const fixedTarget = process.env.VITE_API_TARGET ?? env.VITE_API_TARGET
  const ports = [...new Set([env.SERVER_PORT || '8080', env.BACKEND_HOST_PORT || '8082'])]

  let cached: { target: string | null; at: number } | null = null
  async function liveTarget(): Promise<string | null> {
    if (cached && Date.now() - cached.at < 2000) return cached.target
    let target: string | null = null
    for (const port of ports) {
      if (await listening(Number(port))) {
        target = `http://localhost:${port}`
        break
      }
    }
    cached = { target, at: Date.now() }
    return target
  }

  // The object handed to `configure` is the one the proxy server reads on every request, so updating its `target`
  // in `bypass` (which Vite awaits right before proxying) redirects that request.
  let proxyOptions: ProxyOptions | undefined
  const api: ProxyOptions = fixedTarget
    ? { target: fixedTarget, changeOrigin: true }
    : {
        target: `http://localhost:${ports[0]}`,
        changeOrigin: true,
        configure: (_proxy, options) => {
          proxyOptions = options
        },
        bypass: async (req, res) => {
          const target = await liveTarget()
          if (target) {
            if (proxyOptions) proxyOptions.target = target
            return undefined // proxy as usual
          }
          // No backend at all: say so clearly instead of an opaque 502.
          if (res && !res.headersSent) {
            res.writeHead(503, { 'Content-Type': 'application/json' })
            res.end(
              JSON.stringify({
                status: 503,
                error: 'Service Unavailable',
                message: `Backend is not running (checked localhost:${ports.join(', localhost:')}). Start it with "docker compose up -d" or from the IDE.`,
              }),
            )
          }
          return req.url // response already sent; Vite stops here
        },
      }

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      // Forward /api calls to the Spring Boot backend so the browser sees one origin (no CORS needed).
      proxy: { '/api': api },
    },
  }
})
