import { expect, test as base, type APIRequestContext, type Locator } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset; target: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const project = await (await request.post('/api/projects', { data: { name: `全局边界-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    }
  },
  target: async ({ request, project }, use) => {
    await use(await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'FUNCTIONAL_CASE', name: '全局范围目标', data: { remark: '初始备注' } } })).json() as Asset)
  },
})
async function queue(request: APIRequestContext, changes: unknown[]) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes } } })).ok()).toBe(true)
}
const modification = (target: Asset, remark: string) => ({ operation: 'MODIFY', targetType: target.type, targetId: target.id, baseVersion: target.version, data: { remark } })
async function send(drawer: Locator, feedback: string) {
  await drawer.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill(feedback)
  await drawer.getByRole('button', { name: '生成改进候选', exact: true }).click()
}

test('new global conversation has a distinct ID and no old feedback', async ({ page, request, target, project }) => {
  await page.goto('/cases')
  await page.getByRole('button', { name: '全局反馈', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
  await queue(request, [modification(target, '旧会话候选')])
  const first = page.waitForResponse(response => response.url().endsWith('/api/ai/feedback'))
  await send(drawer, '旧会话独有意见')
  const initial = await (await first).json()
  await expect(drawer.getByText('旧会话候选', { exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: '新建会话', exact: true }).click()
  await queue(request, [modification(target, '新会话候选')])
  const second = page.waitForResponse(response => response.url().endsWith('/api/ai/feedback'))
  await send(drawer, '全新的独立意见')
  const next = await (await second).json()
  await expect(drawer.getByText('新会话候选', { exact: true })).toBeVisible()
  expect(next.conversationId).not.toBe(initial.conversationId)
  const history = await (await request.get(`/api/ai/conversations/${next.conversationId}?projectId=${project.id}`)).json()
  expect(history.messages.filter((message: { role: string }) => message.role === 'user').map((message: { content: string }) => message.content)).toEqual(['全新的独立意见'])
})

test('literal at-key content does not invent an asset dependency', async ({ page, request, target }) => {
  await page.goto('/cases')
  await page.getByRole('button', { name: '全局反馈', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
  await queue(request, [modification(target, '@extra'), { operation: 'ADD', targetType: 'FUNCTIONAL_CASE', localKey: 'extra', name: '无关新增', data: {} }])
  await send(drawer, '备注使用文字 @extra，无需添加其他记录')
  await expect(drawer.getByText('@extra', { exact: true })).toBeVisible()
  await drawer.getByLabel('选择修改 全局范围目标', { exact: true }).check()
  await expect(drawer.getByRole('button', { name: '采纳选中 1 项', exact: true })).toBeEnabled()
  await drawer.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
  await expect(drawer.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
  expect((await (await request.get(`/api/projects/${target.projectId}/assets/${target.id}`)).json()).data.remark).toBe('@extra')
})

test('deleting the entire selected scope keeps the next round empty without broadening to other assets', async ({ page, request, target, project }) => {
  const sibling = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'FUNCTIONAL_CASE', name: '范围之外', data: {} } })).json() as Asset
  await page.goto('/cases')
  await page.getByLabel('选中 全局范围目标', { exact: true }).check()
  await page.getByRole('button', { name: '全局反馈', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
  await queue(request, [{ operation: 'DELETE', targetType: target.type, targetId: target.id, baseVersion: target.version, data: {} }])
  await send(drawer, '删除选中的旧用例')
  await expect(drawer.getByRole('heading', { name: '候选变更', exact: true })).toBeVisible()
  await drawer.getByLabel('选择删除 全局范围目标', { exact: true }).check()
  await drawer.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
  await expect(drawer.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
  await queue(request, [{ operation: 'ADD', targetType: 'FUNCTIONAL_CASE', localKey: 'replacement', name: '新的范围内记录', data: {} }])
  const second = page.waitForResponse(response => response.url().endsWith('/api/ai/feedback'))
  await send(drawer, '在这个范围新增替代记录')
  expect((await second).request().postDataJSON().assetIds).toEqual([])
  await expect(drawer.getByText('新的范围内记录', { exact: true }).first()).toBeVisible()
  expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
})

test('recovery remains busy until the saved candidates arrive and cannot overwrite the next round', async ({ page, request, target }) => {
  let release: (() => void) | undefined
  try {
    await page.goto('/cases')
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await queue(request, [modification(target, '等待恢复的旧候选')])
    await send(drawer, '第一轮')
    await expect(drawer.getByText('等待恢复的旧候选', { exact: true })).toBeVisible()
    await page.reload()
    const held = new Promise<void>(resolve => { release = resolve })
    let reached: (() => void) | undefined
    const requested = new Promise<void>(resolve => { reached = resolve })
    await page.route('**/api/ai/change-sets/*?*', async route => { const response = await route.fetch(); reached?.(); await held; await route.fulfill({ response }) }, { times: 1 })
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    await requested
    await expect(drawer.getByRole('textbox', { name: '全局反馈意见', exact: true })).toBeDisabled()
    release?.()
    await expect(drawer.getByText('等待恢复的旧候选', { exact: true })).toBeVisible()
    await queue(request, [modification(target, '最新一轮候选')])
    await send(drawer, '第二轮')
    await expect(drawer.getByText('最新一轮候选', { exact: true })).toBeVisible()
    await expect(drawer.getByText('等待恢复的旧候选', { exact: true })).toBeHidden()
  } finally { release?.() }
})

test('a late accepted POST remains recoverable without replacing a newer round reference', async ({ page, request, project, target }) => {
  let release: (() => void) | undefined
  try {
    await page.goto('/cases')
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await queue(request, [modification(target, '起始候选')]); await send(drawer, '起始意见')
    await expect(drawer.getByText('起始候选', { exact: true })).toBeVisible()
    const held = new Promise<void>(resolve => { release = resolve })
    let accepted: ((value: { jobId: string; conversationId: string }) => void) | undefined
    const acceptedJob = new Promise<{ jobId: string; conversationId: string }>(resolve => { accepted = resolve })
    await page.route('**/api/ai/feedback', async route => { const response = await route.fetch(); accepted?.(await response.json()); await held; await route.fulfill({ response }) }, { times: 1 })
    await queue(request, [modification(target, '迟到确认的候选')]); await send(drawer, '较早的请求')
    const older = await acceptedJob
    await expect.poll(async () => (await (await request.get(`/api/jobs/${older.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    await drawer.locator('.arco-drawer-close-btn').click()
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    await expect(drawer.getByText('起始候选', { exact: true })).toBeVisible()
    await queue(request, [modification(target, '应当恢复的最新候选')])
    const newerResponse = page.waitForResponse(response => response.url().endsWith('/api/ai/feedback') && response.request().postDataJSON().feedback === '较新的请求')
    await send(drawer, '较新的请求')
    const newer = await (await newerResponse).json()
    await expect(drawer.getByText('应当恢复的最新候选', { exact: true })).toBeVisible()
    const late = page.waitForResponse(response => response.url().endsWith('/api/ai/feedback') && response.request().postDataJSON().feedback === '较早的请求')
    release?.(); await (await late).finished()
    await drawer.locator('.arco-drawer-close-btn').click()
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    await expect(drawer.getByText('应当恢复的最新候选', { exact: true })).toBeVisible()
    expect(await page.evaluate(id => (JSON.parse(localStorage.getItem('ai-test-platform:conversation-index') ?? '[]') as { id: string; jobId: string }[]).find(entry => entry.id === id)?.jobId, newer.conversationId)).toBe(newer.jobId)
  } finally { release?.() }
})
