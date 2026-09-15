import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const response = await request.post('/api/projects', { data: { name: `看板-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
    expect(response.ok()).toBe(true)
    const project = await response.json() as Asset
    const errors: string[] = []
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
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}
function card(id: string, type: string, title: string, fields: string[]) {
  return { schemaVersion: 'aitest.dashboard-card/v1', id, type, title, fields, visible: true, size: 'wide' }
}

test('saved dashboard cards render actual metrics and retain manual title, order, fields and visibility', async ({ page, request, project }, testInfo) => {
  await create(request, project.id, 'FUNCTIONAL_CASE', '查询订单')
  await create(request, project.id, 'FUNCTIONAL_CASE', '取消订单')
  await create(request, project.id, 'BUG', '待处理缺陷')
  await create(request, project.id, 'BUG', '已关闭缺陷', { status: 'CLOSED' })
  const layout = await create(request, project.id, 'DASHBOARD', '我的质量布局', { cards: [
    card('quality', 'quality', '每日质量', ['runCount', 'passRatePercent']),
    card('assets', 'assets', '当前资产', ['FUNCTIONAL_CASE', 'BUG']),
    { id: 'open', type: 'metric', title: '待处理缺陷数', metric: 'openBugs' },
  ], notes: '人工布局备注' })
  const sibling = await create(request, project.id, 'DASHBOARD', '另一个布局', { cards: [] })
  await page.goto('/')
  const board = page.getByRole('region', { name: '项目看板', exact: true })
  await expect(board.getByRole('heading', { name: '每日质量', exact: true })).toBeVisible()
  await expect(board.getByLabel('功能用例数', { exact: true })).toHaveText('2')
  await expect(board.getByLabel('待处理缺陷数数值', { exact: true })).toHaveText('1')
  await expect(board.getByLabel('运行项通过率', { exact: true })).toHaveText('暂无样本')
  await board.getByRole('button', { name: '编辑当前布局', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '我的质量布局', exact: true })
  const editor = dialog.getByRole('region', { name: '看板卡片编辑', exact: true })
  const assets = editor.locator('[data-card-editor-id="assets"]')
  await assets.getByRole('textbox', { name: '卡片标题', exact: true }).fill('核心测试资产')
  await assets.getByRole('combobox', { name: '卡片尺寸', exact: true }).selectOption('full')
  await assets.getByRole('checkbox', { name: '缺陷数', exact: true }).uncheck()
  await assets.getByRole('button', { name: /调整 .* 顺序/ }).press('ArrowUp')
  await editor.locator('[data-card-editor-id="quality"]').getByRole('checkbox', { name: '显示此卡片', exact: true }).uncheck()
  await dialog.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(dialog).toContainText('版本 2')
  await expect.poll(async () => (await (await request.get(`/api/projects/${project.id}/assets/${layout.id}`)).json()).version).toBe('2')
  const saved = await (await request.get(`/api/projects/${project.id}/assets/${layout.id}`)).json() as Asset
  expect((saved.data.cards as { id: string }[]).map(card => card.id)).toEqual(['assets', 'quality', 'open'])
  expect((saved.data.cards as unknown[])[0]).toMatchObject({ title: '核心测试资产', size: 'full', fields: ['FUNCTIONAL_CASE'] })
  expect((saved.data.cards as unknown[])[1]).toMatchObject({ visible: false })
  expect(saved.data.notes).toBe('人工布局备注')
  expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
  await dialog.locator('.arco-drawer-close-btn').click()
  await expect(board.getByRole('heading', { name: '核心测试资产', exact: true })).toBeVisible()
  await expect(board.getByRole('heading', { name: '每日质量', exact: true })).toHaveCount(0)
  await page.reload()
  await expect(board.getByRole('heading', { name: '核心测试资产', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('dashboard-cards-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(async () => page.locator('.app-body').evaluate(element => element.getBoundingClientRect().x)).toBe(0)
  await expect.poll(async () => board.evaluate(element => element.clientWidth)).toBeGreaterThan(330)
  await page.screenshot({ path: testInfo.outputPath('dashboard-cards-mobile.png') })
  expect(await board.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('three AI layout rounds preserve an unsaved manual card draft and show a rebase decision', async ({ page, request, project }) => {
  const originalCards = [card('one', 'quality', '运行质量', ['runCount']), card('two', 'assets', '保留资产卡片', ['FUNCTIONAL_CASE'])]
  const layout = await create(request, project.id, 'DASHBOARD', '反馈布局', { cards: originalCards, notes: '初始备注' })
  const sibling = await create(request, project.id, 'DASHBOARD', '保留布局', { cards: [] })
  await page.goto('/')
  const board = page.getByRole('region', { name: '项目看板', exact: true })
  await board.getByRole('button', { name: '编辑当前布局', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '反馈布局', exact: true })
  const title = detail.locator('[data-card-editor-id="one"]').getByRole('textbox', { name: '卡片标题', exact: true })
  await title.fill('尚未保存的人工标题')
  await detail.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
  const refine = page.getByRole('dialog', { name: 'AI 优化', exact: true })
  for (const round of [1, 2, 3]) {
    expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { notes: `第 ${round} 轮建议` } } } })).ok()).toBe(true)
    await refine.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(`第 ${round} 轮，仅改进布局备注`)
    await refine.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(refine.getByRole('alert')).toContainText(`至版本 ${round + 1}`)
  }
  await refine.locator('.arco-drawer-close-btn').click()
  await expect(detail).toContainText('记录已更新到 v4')
  await expect(title).toHaveValue('尚未保存的人工标题')
  await detail.getByRole('button', { name: '保留我的修改并以最新版本继续', exact: true }).click()
  await detail.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(detail).toContainText('版本 5')
  const saved = await (await request.get(`/api/projects/${project.id}/assets/${layout.id}`)).json() as Asset
  expect(saved.id).toBe(layout.id)
  expect(saved.data.notes).toBe('第 3 轮建议')
  expect((saved.data.cards as unknown[])[0]).toMatchObject({ id: 'one', title: '尚未保存的人工标题' })
  expect((saved.data.cards as unknown[])[1]).toEqual((layout.data.cards as unknown[])[1])
  expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
})

test('recent bug and run cards open their actual records and EvalOps keeps unknown rates', async ({ page, request, project }) => {
  await create(request, project.id, 'BUG', '订单金额错误', { actualResult: '订单金额出现差异' })
  const testcase = await create(request, project.id, 'FUNCTIONAL_CASE', '人工核验订单')
  const plan = await create(request, project.id, 'TEST_PLAN', '看板来源运行', { diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '人工核验项', { targetId: testcase.id, executionMode: 'MANUAL' }, plan.id)
  const submitted = await (await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, idempotencyKey: crypto.randomUUID() } })).json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${submitted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await create(request, project.id, 'DASHBOARD', '记录入口布局', { cards: [card('bugs', 'bugs', '缺陷入口', ['name', 'status']), card('runs', 'runs', '运行入口', ['name', 'status']), card('eval', 'evalops', '真实效能', ['invocations', 'validRatePercent'])] })
  await page.goto('/')
  const board = page.getByRole('region', { name: '项目看板', exact: true })
  await expect(board.getByLabel('模型调用数', { exact: true })).toHaveText('0')
  await expect(board.getByLabel('生成有效率', { exact: true })).toHaveText('暂无样本')
  await board.getByRole('button', { name: '查看运行 看板来源运行', exact: true }).click()
  const run = page.getByRole('dialog', { name: '执行记录', exact: true })
  await expect(run).toContainText('人工核验订单')
  await run.locator('.arco-drawer-close-btn').click()
  await board.getByRole('button', { name: '查看缺陷 订单金额错误', exact: true }).click()
  const bug = page.getByRole('dialog', { name: '订单金额错误', exact: true })
  await expect(bug.getByRole('textbox', { name: '实际结果', exact: true })).toHaveValue('订单金额出现差异')
})
