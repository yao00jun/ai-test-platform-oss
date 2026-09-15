import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const project = await (await request.post('/api/projects', { data: { name: `效能-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
      expect(errors).toEqual([])
    }
  },
})
async function generate(request: APIRequestContext, project: string, content: unknown, usage?: Record<string, number>) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content, usage } })).ok()).toBe(true)
  const submission = await request.post('/api/ai/generate', { data: { projectId: project, type: 'MODULE', instruction: '生成需求模块', idempotencyKey: crypto.randomUUID() } })
  expect(submission.ok()).toBe(true); const job = await submission.json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${job.jobId}?projectId=${project}`)).json()).status).toMatch(/^(SUCCEEDED|FAILED)$/)
}

test('empty sample shows unknown rates and never borrows late metrics from a different project', async ({ page, request, project }) => {
  const other = await (await request.post('/api/projects', { data: { name: `另一效能项目-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  await page.goto('/')
  const panel = page.getByRole('region', { name: 'AI 效能评估', exact: true })
  await expect(panel).toBeVisible()
  await expect(panel.getByLabel('生成有效率', { exact: true })).toHaveText('暂无样本')
  await expect(panel.getByLabel('已报告 Token', { exact: true })).toHaveText('未报告')
  await expect(panel.getByLabel('AI 资产执行通过率', { exact: true })).toHaveText('暂无样本')
  await expect(panel.getByLabel('人工 RCA 正确率', { exact: true })).toHaveText('暂无样本')
  let release!: () => void, entered!: () => void
  const held = new Promise<void>(resolve => { release = resolve }), reached = new Promise<void>(resolve => { entered = resolve })
  await page.route(`**/api/projects/${project.id}/evalops?**`, async route => { const response = await route.fetch(); entered(); await held; try { await route.fulfill({ response }) } catch { /* Switching projects cancels the obsolete read. */ } })
  try {
    await panel.getByRole('button', { name: '刷新效能', exact: true }).click(); await reached
    await page.getByLabel('当前项目', { exact: true }).click()
    await page.getByText(other.name, { exact: true }).click()
    await expect(panel).toHaveAttribute('data-project-id', other.id)
    release()
    await expect(panel.getByLabel('生成有效率', { exact: true })).toHaveText('暂无样本')
    await expect(panel.getByRole('button', { name: '刷新效能', exact: true })).toBeEnabled()
  } finally {
    release(); const latest = await (await request.get(`/api/projects/${other.id}/assets/${other.id}`)).json()
    await request.delete(`/api/projects/${other.id}/assets/${other.id}?baseVersion=${latest.version}`)
  }
})

test('pricing and measured invocations retain historical costs and distinguish valid generation from completed requests', async ({ page, request, project }, info) => {
  const modelName = `eval-ui-${crypto.randomUUID().slice(0, 8)}`
  expect((await request.put('/api/settings/model', { data: { baseUrl: 'http://127.0.0.1:8082/v1', apiKey: 'local-protocol-fixture', modelName } })).ok()).toBe(true)
  try {
    await page.goto('/')
    await page.getByRole('button', { name: '模型设置', exact: true }).click()
    const settings = page.getByRole('dialog', { name: '模型设置', exact: true })
    const pricing = settings.getByRole('region', { name: '模型计价', exact: true })
    await expect(pricing).toContainText(modelName)
    await pricing.getByLabel('启用成本估算', { exact: true }).check()
    await pricing.getByRole('textbox', { name: '输入单价（每百万 Token）', exact: true }).fill('2')
    await pricing.getByRole('textbox', { name: '输出单价（每百万 Token）', exact: true }).fill('3')
    await pricing.getByRole('button', { name: '保存价目', exact: true }).click()
    await expect(pricing).toContainText('价目已保存')
    await settings.locator('.arco-drawer-close-btn').click()
    await generate(request, project.id, { changes: [{ operation: 'ADD', targetType: 'MODULE', localKey: 'm', name: '真实生成模块', data: {} }] }, { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 })
    expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: 'invalid output' } })).ok()).toBe(true)
    await generate(request, project.id, 'invalid repair')
    const panel = page.getByRole('region', { name: 'AI 效能评估', exact: true })
    await panel.getByRole('button', { name: '刷新效能', exact: true }).click()
    await expect(panel.getByLabel('生成有效率', { exact: true })).toHaveText('50%')
    await expect(panel.getByLabel('已报告 Token', { exact: true })).toHaveText('150')
    await expect(panel).toContainText('1 / 3 次调用报告用量')
    await expect(panel).toContainText('2 次生成完成，1 次有效')
    await expect(panel).toContainText('0.00035 CNY')
    await panel.getByRole('button', { name: '查看调用记录', exact: true }).click()
    await expect(panel.getByRole('table', { name: '模型调用记录', exact: true })).toContainText(modelName)
    await expect(panel.getByRole('table', { name: '模型调用记录', exact: true })).toContainText('未报告')
    await page.getByRole('button', { name: '模型设置', exact: true }).click()
    await expect(pricing).toContainText('版本 1')
    await pricing.getByRole('textbox', { name: '输入单价（每百万 Token）', exact: true }).fill('20')
    await pricing.getByRole('textbox', { name: '输出单价（每百万 Token）', exact: true }).fill('30')
    await pricing.getByRole('button', { name: '保存价目', exact: true }).click()
    await expect(pricing).toContainText('版本 2')
    await settings.locator('.arco-drawer-close-btn').click()
    await panel.getByRole('button', { name: '刷新效能', exact: true }).click()
    await expect(panel).toContainText('0.00035 CNY')
    await panel.scrollIntoViewIfNeeded(); await page.screenshot({ path: info.outputPath('evalops-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 }); await panel.scrollIntoViewIfNeeded()
    await page.screenshot({ path: info.outputPath('evalops-mobile.png') })
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  } finally { await request.put('/api/settings/model', { data: { baseUrl: 'http://127.0.0.1:8082/v1', apiKey: 'local-protocol-fixture', modelName: 'e2e-fixture' } }) }
})
