import { ApiClient, ApiError, errorMessage, isRecord } from '../api/client'

export interface SessionState {
  phase: 'loading' | 'locked' | 'ready' | 'error'
  enabled: boolean
  authenticated: boolean
  username: string | null
  csrfToken: string | null
  hasWorkspace: boolean
  workspaceGeneration: number
  busy: boolean
  error: string
}

interface SessionResponse { enabled: boolean; authenticated: boolean; username: string | null; csrfToken: string | null }
function parseSession(value: unknown): SessionResponse {
  if (!isRecord(value) || typeof value.enabled !== 'boolean' || typeof value.authenticated !== 'boolean'
    || !(value.username === null || typeof value.username === 'string')
    || !(value.csrfToken === null || typeof value.csrfToken === 'string') || value.csrfHeader !== 'X-CSRF-TOKEN'
    || (value.enabled && (!value.csrfToken || (value.authenticated && !value.username)))
    || (!value.enabled && !value.authenticated)) {
    throw new ApiError(200, 'INVALID_SESSION_RESPONSE', '无法确认登录状态，请重新连接服务。')
  }
  return { enabled: value.enabled, authenticated: value.authenticated, username: value.username, csrfToken: value.csrfToken }
}

/** Request generations prevent old failures from locking a new login. Reauthentication never replays a write. */
export class PlatformSession {
  state: SessionState = {
    phase: 'loading', enabled: true, authenticated: false, username: null, csrfToken: null,
    hasWorkspace: false, workspaceGeneration: 0, busy: false, error: '',
  }
  private generation = 0

  constructor(private readonly client: ApiClient, private readonly changed: (state: SessionState) => void = () => {}) {
    client.configureAuthentication({
      csrfToken: () => this.state.csrfToken,
      version: () => this.generation,
      canRequest: () => this.state.phase === 'ready' && !this.state.busy,
      rejected: (_error, version) => {
        if (version !== this.generation) return
        void this.refresh().catch(() => { /* The gate displays the connection error and retains drafts. */ })
      },
    })
  }

  private update(patch: Partial<SessionState>): void {
    this.state = { ...this.state, ...patch }
    this.changed(this.state)
  }

  async refresh(): Promise<void> {
    const version = ++this.generation
    this.update({ phase: this.state.hasWorkspace ? 'locked' : 'loading', busy: true, authenticated: false, csrfToken: null, error: '' })
    await this.read(version)
  }

  private async read(version: number): Promise<void> {
    try {
      const data = parseSession(await this.client.request<unknown>('/auth/session', { cache: 'no-store' }))
      if (version !== this.generation) return
      this.update({ ...data, phase: data.authenticated ? 'ready' : 'locked', busy: false, error: '',
        hasWorkspace: this.state.hasWorkspace || data.authenticated })
    } catch (error) {
      if (version === this.generation) this.update({ phase: 'error', busy: false, authenticated: false, csrfToken: null, error: errorMessage(error) })
      throw error
    }
  }

  async login(username: string, password: string): Promise<void> {
    if (this.state.busy) return
    const version = ++this.generation
    this.update({ busy: true, error: '' })
    try {
      await this.client.request('/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) })
      if (version === this.generation) await this.read(version)
    } catch (error) {
      if (version === this.generation) {
        this.update({ phase: 'locked', busy: false, authenticated: false, error: errorMessage(error) })
        if (error instanceof ApiError && error.code === 'CSRF_INVALID') {
          await this.read(version).catch(() => {})
          if (version === this.generation) this.update({ error: errorMessage(error) })
        }
      }
      throw error
    }
  }

  async logout(): Promise<void> {
    if (this.state.busy) return
    const version = ++this.generation
    const previousPhase = this.state.phase
    this.update({ busy: true, error: '' })
    try {
      await this.client.request('/auth/logout', { method: 'POST' })
    } catch (error) {
      if (version === this.generation) {
        this.update({ phase: previousPhase, busy: false, error: errorMessage(error) })
        if (error instanceof ApiError && ['CSRF_INVALID', 'AUTHENTICATION_REQUIRED'].includes(error.code)) {
          await this.refresh().catch(() => {})
        }
      }
      throw error
    }
    if (version !== this.generation) return
    this.update({ phase: 'loading', authenticated: false, username: null, csrfToken: null,
      hasWorkspace: false, workspaceGeneration: this.state.workspaceGeneration + 1 })
    await this.read(version).catch(() => { /* Logout succeeded; the new anonymous bootstrap can be retried. */ })
  }
}
