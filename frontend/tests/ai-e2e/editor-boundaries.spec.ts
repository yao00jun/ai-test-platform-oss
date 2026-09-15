import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

async function projectFor(page: Page, request: APIRequestContext) {
  const response = await request.post('/api/projects', { data: { name: `交互边界-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
  expect(response.ok()).toBe(true)
  const project = await response.json() as Asset
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
  return project
}
async function cleanup(request: APIRequestContext, project: Asset) {
  const response = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
  if (response.ok()) { const latest = await response.json() as Asset; expect((await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${latest.version}`)).status()).toBe(204) }
}

test('unsaved edits retain their base version after confirmation and resolve only with an explicit choice', async ({ page, request }) => {
  const project = await projectFor(page, request)
  try {
    const asset = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'FUNCTIONAL_CASE', name: '人工草稿', data: { precondition: '原始前置', remark: '服务器备注' } } })).json() as Asset
    await page.goto('/cases')
    await page.getByRole('button', { name: '编辑 人工草稿', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '人工草稿', exact: true })
    const precondition = drawer.getByRole('textbox', { name: '前置条件', exact: true })
    await precondition.fill('尚未保存的人工修改')
    const confirm = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${asset.id}`))
    await drawer.getByText('已确认', { exact: true }).click()
    expect((await confirm).status()).toBe(200)
    await expect(precondition).toHaveValue('尚未保存的人工修改')
    const save = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${asset.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    const conflict = await save
    expect(conflict.status()).toBe(409)
    expect(conflict.request().postDataJSON().baseVersion).toBe(asset.version)
    await expect(precondition).toHaveValue('尚未保存的人工修改')
    await drawer.getByRole('button', { name: '保留我的修改并以最新版本继续', exact: true }).click()
    const resolved = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${asset.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    expect((await resolved).status()).toBe(200)
    const latest = await (await request.get(`/api/projects/${project.id}/assets/${asset.id}`)).json() as Asset
    expect(latest).toMatchObject({ confirmed: true, version: '3', data: { precondition: '尚未保存的人工修改', remark: '服务器备注' } })
  } finally { await cleanup(request, project) }
})

test('starting a new local conversation abandons a delayed history read without leaving submission disabled', async ({ page, request }) => {
  const project = await projectFor(page, request)
  let release: (() => void) | undefined
  try {
    const asset = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'FUNCTIONAL_CASE', name: '会话切换', data: {} } })).json() as Asset
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { precondition: '第一轮结果' } } } })
    const accepted = await (await request.post('/api/ai/refine-item', { data: { projectId: project.id, targetType: asset.type, targetId: asset.id, baseVersion: asset.version, feedback: '旧会话意见', applyMode: 'REPLACE_ON_SUCCESS', idempotencyKey: crypto.randomUUID() } })).json()
    await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    await page.addInitScript(reference => localStorage.setItem('ai-test-platform:conversation-index', JSON.stringify([reference])), { id: accepted.conversationId, projectId: project.id, targetKey: asset.id, jobId: accepted.jobId, updatedAt: new Date().toISOString() })
    const held = new Promise<void>(resolve => { release = resolve })
    await page.route(`**/api/ai/conversations/${accepted.conversationId}?*`, async route => {
      const response = await route.fetch()
      await held
      try { await route.fulfill({ response }) } catch { /* The intentionally abandoned request may already be aborted. */ }
    })
    await page.goto('/cases')
    await page.getByRole('button', { name: 'AI 优化 会话切换', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: 'AI 优化', exact: true })
    await expect(drawer.getByText('加载服务端会话…', { exact: true })).toBeVisible()
    await drawer.getByRole('button', { name: '新会话', exact: true }).click()
    await drawer.getByRole('textbox', { name: '本轮反馈', exact: true }).fill('新会话意见')
    await expect(drawer.getByRole('button', { name: '提交反馈', exact: true })).toBeEnabled()
    release?.()
    await expect(drawer.getByText('加载服务端会话…', { exact: true })).toBeHidden()
    await expect(drawer.getByRole('log', { name: 'AI 对话记录', exact: true })).not.toContainText('旧会话意见')
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { remark: '新的独立对话' } } } })
    const nextResponse = page.waitForResponse(response => response.url().endsWith('/api/ai/refine-item'))
    await drawer.getByRole('button', { name: '提交反馈', exact: true }).click()
    const next = await (await nextResponse).json()
    await expect(drawer.getByText('已更新「会话切换」至版本 3，可以继续输入下一轮反馈。', { exact: true })).toBeVisible()
    expect(next.conversationId).not.toBe(accepted.conversationId)
    const history = await (await request.get(`/api/ai/conversations/${next.conversationId}?projectId=${project.id}`)).json()
    expect(history.messages.filter((message: { role: string }) => message.role === 'user').map((message: { content: string }) => message.content)).toEqual(['新会话意见'])
  } finally { release?.(); await cleanup(request, project) }
})

test('reopening a saved preview replaces a different local upload as the reinspection source', async ({ page, request }) => {
  const project = await projectFor(page, request)
  try {
    const saved = await (await request.post(`/api/projects/${project.id}/imports/preview`, { multipart: { type: 'DATASET', format: 'csv', file: { name: 'saved-B.csv', mimeType: 'text/csv', buffer: Buffer.from('编号,内容\r\nB,来自已保存文件\r\n') } } })).json()
    expect(saved.status).toBe('READY')
    await page.goto('/scenarios')
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '导入与导出', exact: true })
    await drawer.getByLabel('选择导入文件', { exact: true }).setInputFiles({ name: 'local-A.csv', mimeType: 'text/csv', buffer: Buffer.from('编号,内容\r\nA,不同的本地文件\r\n') })
    await drawer.locator('summary').filter({ hasText: '继续已有导入预览' }).click()
    await drawer.getByLabel('导入预览 ID', { exact: true }).fill(saved.id)
    await drawer.getByRole('button', { name: '打开预览', exact: true }).click()
    await drawer.locator('summary').filter({ hasText: '数据列名称与类型' }).click()
    await drawer.getByLabel('列 编号 的映射名称', { exact: true }).fill('id')
    const reinspect = page.waitForResponse(response => response.url().endsWith('/imports/preview') && response.request().method() === 'POST')
    await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
    const preview = await (await reinspect).json()
    expect(preview.filename).toBe('saved-B.csv')
    expect(preview.nodes[0].data.rows).toEqual([{ id: 'B', 内容: '来自已保存文件' }])
  } finally { await cleanup(request, project) }
})

test('dropping a new file while a preview is pending cannot replace the chosen source', async ({ page, request }) => {
  const project = await projectFor(page, request)
  let release: (() => void) | undefined
  try {
    await page.goto('/scenarios')
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '导入与导出', exact: true })
    await drawer.getByLabel('选择导入文件', { exact: true }).setInputFiles({ name: 'pending-A.csv', mimeType: 'text/csv', buffer: Buffer.from('编号\r\nA\r\n') })
    const held = new Promise<void>(resolve => { release = resolve })
    let received: (() => void) | undefined
    const reachedServer = new Promise<void>(resolve => { received = resolve })
    await page.route('**/imports/preview', async route => { const response = await route.fetch(); received?.(); await held; await route.fulfill({ response }) })
    await drawer.getByRole('button', { name: '预览并检查', exact: true }).click()
    await reachedServer
    const dataTransfer = await page.evaluateHandle(() => { const data = new DataTransfer(); data.items.add(new File(['编号\r\nB\r\n'], 'dropped-B.csv', { type: 'text/csv' })); return data })
    await drawer.locator('.exchange-drop').dispatchEvent('drop', { dataTransfer })
    release?.()
    await expect(drawer.getByText('可以导入', { exact: true })).toBeVisible()
    await expect(drawer.locator('.exchange-drop')).toContainText('pending-A.csv')
    await expect(drawer.locator('.exchange-drop')).not.toContainText('dropped-B.csv')
  } finally { release?.(); await cleanup(request, project) }
})
