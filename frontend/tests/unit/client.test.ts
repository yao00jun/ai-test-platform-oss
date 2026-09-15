import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { ApiClient, ApiError } from '../../src/api/client'

let client: ApiClient
let baseUrl = ''
let protectedWrites = 0
const server = createServer(async (request: IncomingMessage, response: ServerResponse) => {
  if (request.url === '/api/auth-echo') {
    protectedWrites++
    let body = ''
    for await (const chunk of request) body += chunk.toString()
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ csrf: request.headers['x-csrf-token'], body, contentType: request.headers['content-type'] }))
  } else if (request.url === '/api/conflict') {
    response.writeHead(409, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ code: 'VERSION_CONFLICT', message: '记录已更新', details: { currentVersion: '8' }, requestId: 'request-7' }))
  } else if (request.url === '/api/gateway') {
    response.writeHead(502, { 'Content-Type': 'text/plain' })
    response.end('upstream unavailable')
  } else if (request.url === '/api/export') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Disposition': "attachment; filename*=UTF-8''..%2F..%2F%E6%8A%A5%E5%91%8A.json" })
    response.end('{"actual":"document"}')
  } else if (request.url === '/api/upload') {
    let body = ''
    for await (const chunk of request) body += chunk.toString()
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ contentType: request.headers['content-type'], body }))
  } else if (request.method === 'DELETE') {
    response.writeHead(204)
    response.end()
  } else {
    let body = ''
    for await (const chunk of request) body += chunk.toString()
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ method: request.method, body: JSON.parse(body) }))
  }
})

beforeAll(async () => {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`
  client = new ApiClient(baseUrl)
})
afterAll(async () => new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve())))

describe('HTTP API contract', () => {
  it('attaches the current session CSRF token to actual field mutations', async () => {
    const secured = new ApiClient(baseUrl, { csrfToken: () => 'current-session-token', version: () => 1, canRequest: () => true, rejected: () => {} })
    const response = await secured.request<{ csrf: string; body: string }>('/auth-echo', { method: 'PATCH', body: { name: '人工更新' } })
    expect(response.csrf).toBe('current-session-token')
    expect(JSON.parse(response.body)).toEqual({ name: '人工更新' })
  })
  it('encodes login forms without dropping non-ASCII or special characters', async () => {
    const form = new URLSearchParams({ username: '用户+一', password: 'a &+b=中文' })
    const response = await client.request<{ body: string; contentType: string }>('/auth-echo', { method: 'POST', body: form })
    expect(response.contentType).toMatch(/^application\/x-www-form-urlencoded/)
    expect(Object.fromEntries(new URLSearchParams(response.body))).toEqual({ username: '用户+一', password: 'a &+b=中文' })
  })
  it('does not send a write while the workspace is locked for reauthentication', async () => {
    const secured = new ApiClient(baseUrl, { csrfToken: () => null, version: () => 2, canRequest: () => false, rejected: () => {} })
    const before = protectedWrites
    await expect(secured.request('/auth-echo', { method: 'POST', body: { name: '保留草稿' } })).rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
    expect(protectedWrites).toBe(before)
  })
  it('surfaces actionable conflict details and request IDs instead of treating failures as data', async () => {
    const error = await client.request('/conflict').catch((value: unknown) => value)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'VERSION_CONFLICT', message: '记录已更新', details: { currentVersion: '8' }, requestId: 'request-7' })
  })
  it('serializes field patches without an extra response/request envelope', async () => {
    expect(await client.request('/asset', { method: 'PATCH', body: { baseVersion: '4', data: { expected: '' } } })).toEqual({
      method: 'PATCH', body: { baseVersion: '4', data: { expected: '' } },
    })
  })
  it('accepts empty successful delete responses', async () => {
    expect(await client.request('/asset', { method: 'DELETE' })).toBeUndefined()
  })
  it('preserves HTTP failure status when the proxy returns non-JSON', async () => {
    await expect(client.request('/gateway')).rejects.toMatchObject({ status: 502, code: 'HTTP_ERROR' })
  })
  it('downloads actual JSON documents with a safe decoded filename', async () => {
    const file = await client.download('/export')
    expect(file.filename).toBe('报告.json')
    expect(await file.blob.text()).toBe('{"actual":"document"}')
  })
  it('never saves JSON errors as successful exported files', async () => {
    await expect(client.download('/conflict')).rejects.toMatchObject({ status: 409, code: 'VERSION_CONFLICT' })
  })
  it('sends multipart files intact with the browser-generated boundary', async () => {
    const form = new FormData()
    form.append('type', 'REQUIREMENT')
    form.append('file', new Blob(['# 退款\n原始需求']), 'prd.md')
    const result = await client.request<{ contentType: string; body: string }>('/upload', { method: 'POST', body: form })
    expect(result.contentType).toMatch(/^multipart\/form-data; boundary=/)
    expect(result.body).toContain('name="file"; filename="prd.md"')
    expect(result.body).toContain('# 退款\n原始需求')
    expect(result.body).toContain('REQUIREMENT')
  })
})
