import { expect, test as base, type APIRequestContext } from '@playwright/test'
import { createServer } from 'node:http'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    const response = await request.post('/api/projects', { data: { name: `通知验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
    expect(response.ok()).toBe(true)
    const project = await response.json() as Asset, errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
      expect(errors).toEqual([])
    }
  },
})
async function create(request: APIRequestContext, project: string, type: string, name: string, data = {}, parentId?: string) {
  const response = await request.post(`/api/projects/${project}/assets`, { data: { type, name, data, parentId } })
  expect(response.ok()).toBe(true); return await response.json() as Asset
}
const historyPath = (project: string, webhook: string) => `/api/projects/${project}/webhooks/${webhook}/deliveries`

test('disabled webhook keeps its unsaved fields when opening delivery history', async ({ page, request, project }, testInfo) => {
  await create(request, project.id, 'WEBHOOK', '人工通知配置')
  await page.goto('/projects')
  await page.getByRole('tab', { name: '群通知', exact: true }).click()
  await page.getByRole('button', { name: '编辑 人工通知配置', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '人工通知配置', exact: true })
  await detail.getByRole('textbox', { name: '名称', exact: true }).fill('保留未保存的通知名称')
  await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
  const panel = detail.getByRole('region', { name: '群通知发送记录', exact: true })
  await expect(panel).toContainText('尚未启用')
  await expect(panel).toContainText('暂无发送记录')
  await detail.getByRole('tab', { name: '详情与编辑', exact: true }).click()
  await expect(detail.getByRole('textbox', { name: '名称', exact: true })).toHaveValue('保留未保存的通知名称')
  await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('notification-disabled-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => detail.locator('.arco-drawer-body').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(330)
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('notification-disabled-mobile.png') })
})

test('unknown delivery requires acknowledgement and recovers a lost manual retry response without duplicate sends', async ({ page, request, project }, testInfo) => {
  let received = 0
  const receiver = createServer(async (req, res) => {
    for await (const chunk of req) { void chunk }
    received += 1; res.writeHead(200, { 'content-type': 'application/json' }); res.end(received === 1 ? 'unknown acknowledgement' : '{"errcode":0}')
  })
  await new Promise<void>(resolve => receiver.listen(0, '127.0.0.1', resolve))
  try {
    const address = receiver.address(); if (!address || typeof address === 'string') throw new Error('Missing receiver port')
    const webhook = await create(request, project.id, 'WEBHOOK', '实际测试通知', { enabled: true, webhookUrl: `http://127.0.0.1:${address.port}/robot?token=local-private-token` })
    const api = await create(request, project.id, 'API_CASE', '失败接口', { path: 'http://127.0.0.1:8082/__business/orders', method: 'POST', body: { qty: 1 }, assertions: [{ type: 'status', expected: 200 }] })
    const plan = await create(request, project.id, 'TEST_PLAN', '群通知验收计划', { diagnoseFailures: false })
    await create(request, project.id, 'PLAN_ITEM', '接口项', { targetId: api.id }, plan.id)
    const submitted = await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, idempotencyKey: crypto.randomUUID() } }); expect(submitted.ok()).toBe(true)
    await expect.poll(async () => (await (await request.get(historyPath(project.id, webhook.id))).json()).items[0]?.status).toBe('UNCERTAIN')
    await page.goto('/projects'); await page.getByRole('tab', { name: '群通知', exact: true }).click()
    await page.getByRole('button', { name: '编辑 实际测试通知', exact: true }).click()
    const detail = page.getByRole('dialog', { name: '实际测试通知', exact: true })
    await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
    const panel = detail.getByRole('region', { name: '群通知发送记录', exact: true })
    await expect(panel).toContainText('送达状态未知')
    await expect(panel).not.toContainText('local-private-token')
    await expect(panel.getByRole('button', { name: '使用当前配置重试', exact: true })).toBeDisabled()
    await panel.getByRole('checkbox', { name: '已检查群消息，接受可能重复发送', exact: true }).check()
    let lost = false
    await page.route('**/webhooks/*/deliveries/*/retry', async route => {
      if (!lost) { lost = true; const response = await route.fetch(); expect(response.ok()).toBe(true); await route.abort('failed') } else await route.continue()
    })
    await panel.getByRole('button', { name: '使用当前配置重试', exact: true }).click()
    await expect(panel.getByRole('alert')).toBeVisible()
    await page.reload(); await page.getByRole('tab', { name: '群通知', exact: true }).click()
    await page.getByRole('button', { name: '编辑 实际测试通知', exact: true }).click(); await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
    await panel.getByRole('button', { name: '确认上次重试结果', exact: true }).click()
    await expect(panel.getByTestId('notification-status')).toHaveText('已送达')
    const item = (await (await request.get(historyPath(project.id, webhook.id))).json()).items[0]
    expect(item.attemptCount).toBe(2); expect(received).toBe(2)
    await panel.getByRole('button', { name: '展开发送尝试', exact: true }).click()
    await expect(panel.getByRole('list', { name: '发送尝试', exact: true }).getByRole('listitem')).toHaveCount(2)
    await page.screenshot({ path: testInfo.outputPath('notification-history-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => detail.locator('.arco-drawer-body').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(330)
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('notification-history-mobile.png') })
    await panel.getByRole('button', { name: '查看来源运行', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '执行记录', exact: true }).getByTestId('run-status')).toHaveText('失败')
  } finally { receiver.closeAllConnections(); await new Promise<void>(resolve => receiver.close(() => resolve())) }
})

test('retry configuration conflicts preserve unsaved manual fields and use the reviewed current version', async ({ page, request, project }) => {
  let received = 0
  const receiver = createServer(async (req, res) => {
    for await (const chunk of req) { void chunk }
    received++; res.writeHead(200, { 'content-type': 'application/json' }); res.end(received === 1 ? '{}' : '{"errcode":0}')
  })
  await new Promise<void>(resolve => receiver.listen(0, '127.0.0.1', resolve))
  try {
    const address = receiver.address(); if (!address || typeof address === 'string') throw new Error('Missing receiver port')
    const webhook = await create(request, project.id, 'WEBHOOK', '并发通知配置', { enabled: true, webhookUrl: `http://127.0.0.1:${address.port}/robot` })
    const api = await create(request, project.id, 'API_CASE', '失败接口', { path: 'http://127.0.0.1:8082/__business/orders', method: 'POST', body: { qty: 1 }, assertions: [{ type: 'status', expected: 200 }] })
    const plan = await create(request, project.id, 'TEST_PLAN', '通知版本验证', { diagnoseFailures: false })
    await create(request, project.id, 'PLAN_ITEM', '接口项', { targetId: api.id }, plan.id)
    expect((await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, idempotencyKey: crypto.randomUUID() } })).ok()).toBe(true)
    await expect.poll(async () => (await (await request.get(historyPath(project.id, webhook.id))).json()).items[0]?.status).toBe('UNCERTAIN')
    await page.goto('/projects'); await page.getByRole('tab', { name: '群通知', exact: true }).click()
    await page.getByRole('button', { name: '编辑 并发通知配置', exact: true }).click()
    const detail = page.getByRole('dialog', { name: '并发通知配置', exact: true })
    await detail.getByRole('textbox', { name: '名称', exact: true }).fill('还未保存的人工名称')
    await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
    const panel = detail.getByRole('region', { name: '群通知发送记录', exact: true })
    expect((await request.patch(`/api/projects/${project.id}/assets/${webhook.id}`, { data: { baseVersion: '1', data: { maxRetries: 0 } } })).ok()).toBe(true)
    await panel.getByRole('checkbox', { name: '已检查群消息，接受可能重复发送', exact: true }).check()
    await panel.getByRole('button', { name: '使用当前配置重试', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('已更新')
    expect(received).toBe(1)
    await panel.getByRole('button', { name: '读取最新通知配置', exact: true }).click()
    await expect(panel).toContainText('当前配置 v2')
    await detail.getByRole('tab', { name: '详情与编辑', exact: true }).click()
    await expect(detail.getByRole('textbox', { name: '名称', exact: true })).toHaveValue('还未保存的人工名称')
    await detail.getByRole('tab', { name: '发送记录', exact: true }).click()
    await panel.getByRole('checkbox', { name: '已检查群消息，接受可能重复发送', exact: true }).check()
    await panel.getByRole('button', { name: '使用当前配置重试', exact: true }).click()
    await expect(panel.getByTestId('notification-status')).toHaveText('已送达')
    expect(received).toBe(2)
    const item = (await (await request.get(historyPath(project.id, webhook.id))).json()).items[0]
    expect(item.attempts.map((attempt: { configVersion: string }) => attempt.configVersion)).toEqual(['1', '2'])
    expect(item.maxRetries).toBe(3)
  } finally { receiver.closeAllConnections(); await new Promise<void>(resolve => receiver.close(() => resolve())) }
})
