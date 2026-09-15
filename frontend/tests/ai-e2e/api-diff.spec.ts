import { expect, test as base, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    const project = await (await request.post('/api/projects', { data: { name: `接口变更-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    }
  },
})
function document(field: string) {
  return JSON.stringify({ openapi: '3.0.3', info: { title: '资料接口', version: '1' }, paths: {
    '/users': { post: { operationId: 'updateUser', summary: '更新资料', requestBody: { content: { 'application/json': { schema: { type: 'object', required: [field], properties: { [field]: { type: 'string' } } } } } }, responses: { 200: { description: 'OK' } } } },
    '/health': { get: { operationId: 'health', summary: '健康状态', responses: { 200: { description: 'OK' } } } },
  } })
}
async function seed(request: APIRequestContext, project: string) {
  const preview = await request.post(`/api/projects/${project}/imports/preview`, { multipart: { type: 'API_DEFINITION', format: 'json', file: { name: 'before.json', mimeType: 'application/json', buffer: Buffer.from(document('userId')) } } })
  expect(preview.ok()).toBe(true)
  const applied = await request.post(`/api/projects/${project}/imports/${(await preview.json()).id}/apply`, { data: {} })
  expect(applied.ok()).toBe(true)
  const ids = (await applied.json()).keyToId as Record<string, string>
  return ids.api_1!
}
async function openDiff(page: Page, field: string) {
  await page.getByRole('button', { name: '接口变更对比', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '接口变更对比', exact: true })
  const fileInput = dialog.getByLabel('选择新版接口文档', { exact: true })
  await expect(fileInput).toBeEnabled()
  await fileInput.setInputFiles({ name: 'updated.json', mimeType: 'application/json', buffer: Buffer.from(document(field)) })
  await dialog.getByRole('button', { name: '预检并对比', exact: true }).click()
  await expect(dialog.getByRole('region', { name: '接口差异', exact: true })).toContainText(field)
  await dialog.getByLabel('选择接口变更 更新资料', { exact: true }).check()
  return dialog
}

test('API diff adopts stable definitions then previews repeated scoped healing while preserving manual and unrelated assets', async ({ page, request, project }) => {
  const definitionId = await seed(request, project.id)
  const create = async (name: string, data: Record<string, unknown>) => await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'API_CASE', name, data } })).json() as Asset
  let target = await create('资料请求', { apiDefinitionId: definitionId, path: '/users', method: 'POST', bodyType: 'JSON', body: { userId: 'human-value' } })
  const sibling = await create('保留用例', { path: '/independent' })
  await page.goto('/api-tests')
  const dialog = await openDiff(page, 'uid')
  await dialog.getByRole('button', { name: '采纳选中接口变更', exact: true }).click()
  await expect(dialog.getByRole('alert').filter({ hasText: '已采纳 1 项接口变更' })).toBeVisible()
  expect((await (await request.get(`/api/projects/${project.id}/assets/${definitionId}`)).json()).version).toBe('2')
  expect(await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json()).toEqual(target)
  await dialog.getByRole('button', { name: 'AI 定向修复', exact: true }).click()
  const ai = page.getByRole('dialog', { name: '接口 AI 修复', exact: true })
  for (let round = 1; round <= 2; round++) {
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [{ operation: 'MODIFY', targetType: target.type, targetId: target.id, baseVersion: target.version, data: { body: { uid: 'human-value', round } } }] } } })
    await ai.getByRole('textbox', { name: '接口修复意见', exact: true }).fill(`只改 uid，保留人工值，第 ${round} 轮`)
    await ai.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(ai.getByRole('region', { name: '候选变更预览', exact: true })).toContainText('资料请求')
    expect(await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json()).toEqual(target)
    await ai.getByLabel('选择修改 资料请求', { exact: true }).check()
    await ai.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
    await expect(ai.getByRole('alert').filter({ hasText: '已采纳 1 项变更' })).toBeVisible()
    target = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    if (round === 1) target = await (await request.patch(`/api/projects/${project.id}/assets/${target.id}`, { data: { baseVersion: target.version, data: { timeoutMs: 12345 } } })).json() as Asset
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
  }
  expect(target.data.timeoutMs).toBe(12345)
  await ai.locator('.arco-drawer-close-btn').click()
  await dialog.locator('.arco-drawer-close-btn').click()
  await page.reload()
  await page.getByRole('button', { name: '接口变更对比', exact: true }).click()
  await expect(dialog.getByRole('region', { name: '接口差异', exact: true })).toContainText('已采纳')
  await dialog.getByRole('button', { name: 'AI 定向修复', exact: true }).click()
  await expect(ai.getByText('多轮反馈记录 · 2 轮', { exact: true })).toBeVisible()
  await expect(ai.getByRole('region', { name: '候选变更预览', exact: true })).toContainText('已采纳')
  await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [], reason: '接口字段已一致，无需继续修改' } } })
  await ai.getByRole('textbox', { name: '接口修复意见', exact: true }).fill('复核当前字段，已经一致则保持原状')
  await ai.getByRole('button', { name: '生成改进候选', exact: true }).click()
  await expect(ai.getByRole('alert').filter({ hasText: '当前反馈无需修改已有资产' })).toBeVisible()
  await expect(ai.getByRole('region', { name: '候选变更预览', exact: true })).toHaveCount(0)
  expect(await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json()).toEqual(target)
  expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
})

test('uncertain API adoption reuses its request key and a later human version blocks stale changes', async ({ page, request, project }) => {
  const definitionId = await seed(request, project.id)
  await page.goto('/api-tests')
  const dialog = await openDiff(page, 'uid')
  const keys: string[] = []
  await page.route('**/api-diffs/*/apply', async route => {
    keys.push(route.request().postDataJSON().idempotencyKey)
    if (keys.length === 1) { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }
    else await route.continue()
  })
  await dialog.getByRole('button', { name: '采纳选中接口变更', exact: true }).click()
  await expect(dialog.getByRole('alert').filter({ hasText: /连接|请求|网络/ })).toBeVisible()
  await dialog.getByRole('button', { name: '采纳选中接口变更', exact: true }).click()
  await expect(dialog.getByRole('alert').filter({ hasText: '已采纳 1 项接口变更' })).toBeVisible()
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1])
  let definition = await (await request.get(`/api/projects/${project.id}/assets/${definitionId}`)).json() as Asset
  expect(definition.version).toBe('2')
  await dialog.locator('.arco-drawer-close-btn').click()
  await openDiff(page, 'accountId')
  definition = await (await request.patch(`/api/projects/${project.id}/assets/${definitionId}`, { data: { baseVersion: definition.version, name: '人工保留名称' } })).json() as Asset
  await dialog.getByRole('button', { name: '采纳选中接口变更', exact: true }).click()
  await expect(dialog.getByRole('alert').filter({ hasText: '接口定义已变化' })).toBeVisible()
  expect(await (await request.get(`/api/projects/${project.id}/assets/${definitionId}`)).json()).toEqual(definition)
})
