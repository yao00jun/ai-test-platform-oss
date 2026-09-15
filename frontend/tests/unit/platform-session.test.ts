import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { ApiClient } from '../../src/api/client'
import { PlatformSession } from '../../src/core/platform-session'

function barrier() {
  let release!: () => void
  const promise = new Promise<void>(resolve => { release = resolve })
  return { promise, release }
}
let server: Server
let client: ApiClient
let session: PlatformSession
let authenticated: boolean
let enabled: boolean
let generation: number
let writes: number
let attempts: number
let failLogout: boolean
let malformed: boolean
let delayedRead: ReturnType<typeof barrier> | undefined
let lateFailure: ReturnType<typeof barrier> | undefined
let heldReads: number

beforeEach(async () => {
  authenticated = false; enabled = true; generation = 1; writes = 0; attempts = 0
  failLogout = false; malformed = false; heldReads = 0; delayedRead = undefined; lateFailure = undefined
  server = createServer(async (request, response) => {
    let body = ''
    for await (const chunk of request) body += chunk.toString()
    function reply(status: number, data?: unknown) {
      response.writeHead(status, { 'Content-Type': 'application/json' })
      response.end(data === undefined ? undefined : JSON.stringify(data))
    }
    const token = `csrf-${generation}`
    if (request.url === '/api/auth/session') {
      const data = malformed ? { authenticated: true } : {
        enabled, authenticated: !enabled || authenticated, username: authenticated ? 'owner' : null,
        csrfToken: enabled ? token : null, csrfHeader: 'X-CSRF-TOKEN',
      }
      const hold = delayedRead
      if (hold) { delayedRead = undefined; heldReads++; await hold.promise }
      reply(200, data); return
    }
    if (request.url === '/api/late') {
      const hold = lateFailure
      heldReads++
      if (hold) await hold.promise
      reply(401, { code: 'AUTHENTICATION_REQUIRED', message: '旧请求的会话已失效' }); return
    }
    if (request.url === '/api/auth/login') {
      if (request.headers['x-csrf-token'] !== token) { reply(403, { code: 'CSRF_INVALID', message: '校验已失效' }); return }
      const input = new URLSearchParams(body)
      if (input.get('username') !== 'owner' || input.get('password') !== 'password + 中文') { reply(401, { code: 'INVALID_CREDENTIALS', message: '用户名或密码不正确' }); return }
      authenticated = true; generation++; reply(200, { authenticated: true }); return
    }
    if (request.url === '/api/auth/logout') {
      if (failLogout) { reply(503, { code: 'TEMPORARY_FAILURE', message: '服务暂不可用' }); return }
      if (request.headers['x-csrf-token'] !== token) { reply(403, { code: 'CSRF_INVALID', message: '校验已失效' }); return }
      authenticated = false; generation++; reply(204); return
    }
    attempts++
    if (enabled && !authenticated) { reply(401, { code: 'AUTHENTICATION_REQUIRED', message: '请重新登录' }); return }
    if (enabled && request.headers['x-csrf-token'] !== token) { reply(403, { code: 'CSRF_INVALID', message: '校验已失效' }); return }
    writes++; reply(200, { saved: true })
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  client = new ApiClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/api`)
  session = new PlatformSession(client)
})
afterEach(async () => {
  delayedRead?.release(); lateFailure?.release(); server.closeAllConnections()
  await new Promise<void>(resolve => server.close(() => resolve()))
})

it('requires login before opening the workspace and uses the rotated token for manual writes', async () => {
  await session.refresh()
  expect(session.state).toMatchObject({ phase: 'locked', hasWorkspace: false, username: null })
  await session.login('owner', 'password + 中文')
  expect(session.state).toMatchObject({ phase: 'ready', hasWorkspace: true, username: 'owner', csrfToken: 'csrf-2' })
  expect(await client.request('/asset', { method: 'PATCH', body: { name: '人工更改' } })).toEqual({ saved: true })
  expect(writes).toBe(1)
})

it('retains workspace identity on expiry and never replays a rejected write after login', async () => {
  authenticated = true; await session.refresh()
  const identity = session.state.workspaceGeneration
  authenticated = false
  await expect(client.request('/asset', { method: 'PATCH', body: { name: '草稿' } })).rejects.toMatchObject({ status: 401 })
  await expect.poll(() => session.state.busy).toBe(false)
  expect(session.state).toMatchObject({ phase: 'locked', hasWorkspace: true, workspaceGeneration: identity })
  await expect(client.request('/asset', { method: 'PATCH' })).rejects.toMatchObject({ status: 401 })
  expect(attempts).toBe(1)
  await session.login('owner', 'password + 中文')
  expect(session.state).toMatchObject({ phase: 'ready', hasWorkspace: true, workspaceGeneration: identity })
  expect(writes).toBe(0)
  await client.request('/asset', { method: 'PATCH', body: { name: '草稿' } })
  expect(writes).toBe(1)
})

it('keeps the gate closed after wrong credentials with an actionable error', async () => {
  await session.refresh()
  await expect(session.login('owner', 'wrong')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
  expect(session.state).toMatchObject({ phase: 'locked', hasWorkspace: false, busy: false, error: '用户名或密码不正确' })
  await session.login('owner', 'password + 中文')
  expect(session.state.phase).toBe('ready')
})

it('clears workspace identity only after a successful deliberate logout', async () => {
  authenticated = true; await session.refresh()
  failLogout = true
  await expect(session.logout()).rejects.toMatchObject({ status: 503 })
  expect(session.state).toMatchObject({ phase: 'ready', hasWorkspace: true, workspaceGeneration: 0 })
  failLogout = false; await session.logout()
  expect(session.state).toMatchObject({ phase: 'locked', hasWorkspace: false, username: null, workspaceGeneration: 1 })
})

it('ignores a delayed bootstrap response when a newer session read already completed', async () => {
  const hold = barrier(); delayedRead = hold
  const old = session.refresh()
  await expect.poll(() => heldReads).toBe(1)
  authenticated = true
  await session.refresh()
  hold.release(); await old
  expect(session.state).toMatchObject({ phase: 'ready', authenticated: true, hasWorkspace: true })
})

it('does not relock a new login because an older protected request returns 401 late', async () => {
  authenticated = true; await session.refresh()
  lateFailure = barrier()
  const old = client.request('/late').catch(error => error)
  await expect.poll(() => heldReads).toBe(1)
  authenticated = false
  await expect(client.request('/asset', { method: 'PATCH' })).rejects.toMatchObject({ status: 401 })
  await expect.poll(() => session.state.busy).toBe(false)
  await session.login('owner', 'password + 中文')
  lateFailure.release(); await old
  expect(session.state).toMatchObject({ phase: 'ready', authenticated: true, hasWorkspace: true })
})

it('refreshes a stale CSRF token without resubmitting the failed mutation', async () => {
  authenticated = true; await session.refresh(); generation++
  await expect(client.request('/asset', { method: 'PATCH' })).rejects.toMatchObject({ code: 'CSRF_INVALID' })
  await expect.poll(() => session.state.phase).toBe('ready')
  expect(writes).toBe(0); expect(attempts).toBe(1)
  await client.request('/asset', { method: 'PATCH' })
  expect(writes).toBe(1)
})

it('never treats a malformed session response as permission to open the workspace', async () => {
  malformed = true
  await expect(session.refresh()).rejects.toMatchObject({ code: 'INVALID_SESSION_RESPONSE' })
  expect(session.state).toMatchObject({ phase: 'error', hasWorkspace: false })
  await expect(client.request('/asset', { method: 'PATCH' })).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })
  expect(attempts).toBe(0)
})

it('allows the existing local mode without a login or CSRF token', async () => {
  enabled = false; await session.refresh()
  expect(session.state).toMatchObject({ enabled: false, phase: 'ready', hasWorkspace: true, csrfToken: null })
  expect(await client.request('/asset', { method: 'POST' })).toEqual({ saved: true })
})
