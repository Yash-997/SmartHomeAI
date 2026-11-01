import { connect } from 'node:net'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

/** True if something accepts TCP connections on localhost:port (checked once, when the dev server starts). */
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
export default defineConfig(async ({ mode }) => {
  const env = loadEnv(mode, '..', '') // the project-root .env
  // An explicit VITE_API_TARGET always wins. Otherwise use the first backend that is actually running: one started
  // from the IDE/Maven (SERVER_PORT, default 8080) or the Docker Compose one (published on BACKEND_HOST_PORT, default 8082).
  let target = process.env.VITE_API_TARGET ?? env.VITE_API_TARGET
  if (!target) {
    const candidates = [env.SERVER_PORT || '8080', env.BACKEND_HOST_PORT || '8082']
    let port = candidates[0]
    for (const c of candidates) {
      if (await listening(Number(c))) {
        port = c
        break
      }
    }
    target = `http://localhost:${port}`
  }
  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      // Forward /api calls to the Spring Boot backend so the browser sees one origin (no CORS needed).
      proxy: {
        '/api': target,
      },
    },
  }
})
