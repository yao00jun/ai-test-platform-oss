export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: unknown,
    public requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions extends Omit<RequestInit, 'body'> { body?: unknown }
export interface DownloadFile { blob: Blob; filename: string }
export interface ClientAuthentication {
  csrfToken(): string | null
  version(): number
  canRequest(): boolean
  rejected(error: ApiError, version: number): void
}

export class ApiClient {
  constructor(private readonly baseUrl = '/api', private authentication?: ClientAuthentication) {}

  configureAuthentication(authentication: ClientAuthentication): void { this.authentication = authentication }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.fetch(path, options)
    if (response.status === 204) return undefined as T
    try { return await response.json() as T }
    catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的响应。') }
  }

  async download(path: string, options: RequestOptions = {}): Promise<DownloadFile> {
    const response = await this.fetch(path, options)
    const blob = await response.blob()
    if (!blob.size) throw new ApiError(response.status, 'EMPTY_DOWNLOAD', '下载文件为空，请重新导出。')
    const disposition = response.headers.get('Content-Disposition') ?? ''
    const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)?.[1]
    let filename = /filename\s*=\s*"([^"]+)"/i.exec(disposition)?.[1] ?? /filename\s*=\s*([^;]+)/i.exec(disposition)?.[1] ?? 'download'
    if (encoded) { try { filename = decodeURIComponent(encoded.trim()) } catch { /* Use the ordinary filename for malformed extensions. */ } }
    filename = filename.replaceAll('\\', '/').split('/').pop()?.replace(/[<>:"|?*]/g, '_').split('').map(char => char.charCodeAt(0) < 32 ? '_' : char).join('').trim() || 'download'
    return { blob, filename }
  }

  private async fetch(path: string, options: RequestOptions): Promise<Response> {
    const authentication = this.authentication
    const sessionVersion = authentication?.version() ?? 0
    const sessionRoute = path.startsWith('/auth/')
    if (!sessionRoute && authentication && !authentication.canRequest()) {
      throw new ApiError(401, 'AUTHENTICATION_REQUIRED', '请先登录后再提交；当前编辑仍保留在本标签页。')
    }
    const headers = new Headers(options.headers)
    headers.set('Accept', 'application/json')
    const method = (options.method ?? 'GET').toUpperCase()
    const csrf = authentication?.csrfToken()
    if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-CSRF-TOKEN', csrf)
    const multipart = options.body instanceof FormData
    const encodedForm = options.body instanceof URLSearchParams
    if (multipart) headers.delete('Content-Type')
    else if (encodedForm) headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8')
    else if (options.body !== undefined) headers.set('Content-Type', 'application/json')
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        ...options,
        credentials: 'same-origin',
        headers,
        body: multipart ? options.body as FormData : encodedForm ? (options.body as URLSearchParams).toString()
          : options.body === undefined ? undefined : JSON.stringify(options.body),
      })
    } catch (error) {
      if (isAbortError(error)) throw error
      throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查后端是否启动后重试。')
    }
    if (!response.ok) {
      let body: unknown
      try { body = await response.json() } catch { body = undefined }
      const detail = isRecord(body) ? body : {}
      const error = new ApiError(
        response.status,
        typeof detail.code === 'string' ? detail.code : 'HTTP_ERROR',
        typeof detail.message === 'string' ? detail.message : `服务返回 HTTP ${response.status}，请稍后重试。`,
        detail.details,
        typeof detail.requestId === 'string' ? detail.requestId : response.headers.get('X-Request-Id') ?? undefined,
      )
      if (!sessionRoute && (error.code === 'AUTHENTICATION_REQUIRED' || error.code === 'CSRF_INVALID')) authentication?.rejected(error, sessionVersion)
      throw error
    }
    return response
  }
}

export function saveDownload(file: DownloadFile) {
  const url = URL.createObjectURL(file.blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = file.filename
  document.body.append(anchor); anchor.click(); anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
export function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请重试。'
}
export function queryString(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) if (value !== undefined) search.set(key, String(value))
  return search.size ? `?${search}` : ''
}
export const http = new ApiClient()
