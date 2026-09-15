import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'
import type { RunDetail } from '../../src/api/runs'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const project = await (await request.post('/api/projects', { data: { name: `质量简报-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
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
async function create(request: APIRequestContext, projectId: string, type: string, name: string, data = {}, parentId?: string) {
  const response = await request.post(`/api/projects/${projectId}/assets`, { data: { type, name, data, parentId } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}
async function queue(request: APIRequestContext, content: unknown) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content } })).ok()).toBe(true)
}

test('selected-run quality brief retains factual statistics through manual editing and multiple local feedback rounds', async ({ page, request, project }, testInfo) => {
  const first = await create(request, project.id, 'FUNCTIONAL_CASE', '核对账单')
  const second = await create(request, project.id, 'FUNCTIONAL_CASE', '核对退款')
  const plan = await create(request, project.id, 'TEST_PLAN', '质量核验计划', { diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '账单项', { targetId: first.id, executionMode: 'MANUAL' }, plan.id)
  await create(request, project.id, 'PLAN_ITEM', '退款项', { targetId: second.id, executionMode: 'MANUAL' }, plan.id)
  const sibling = await create(request, project.id, 'QUALITY_BRIEF', '人工保留简报', { content: '原有结论' })
  const accepted = await (await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, idempotencyKey: crypto.randomUUID() } })).json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  const initial = await (await request.get(`/api/projects/${project.id}/runs/${accepted.runId}`)).json() as RunDetail
  expect(initial.items).toHaveLength(2)
  for (const [index, item] of initial.items.entries()) {
    expect((await request.post(`/api/projects/${project.id}/runs/${initial.id}/manual-result`, { data: { itemId: item.id, baseVersion: item.manualVersion, status: index === 0 ? 'PASSED' : 'FAILED', notes: index === 0 ? '已核对' : '退款金额不一致' } })).ok()).toBe(true)
  }
  await queue(request, { changes: [{ operation: 'ADD', targetType: 'QUALITY_BRIEF', localKey: 'brief', name: '本次运行质量分析', data: { content: '一项通过，一项失败。退款金额需要核实。' } }] })
  await page.goto('/plans')
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  const execution = page.getByRole('dialog', { name: '执行记录', exact: true }).first()
  await execution.getByRole('button', { name: '查看运行 质量核验计划', exact: true }).click()
  await expect(execution.getByTestId('run-status')).toHaveText('失败')
  await execution.getByRole('button', { name: 'AI 质量报告', exact: true }).click()
  const generate = page.getByRole('dialog', { name: 'AI 生成', exact: true })
  await expect(generate).toContainText('当前运行')
  await generate.getByRole('textbox', { name: '生成要求', exact: true }).fill('分析此次运行的退款风险，并说明待核验事项')
  const submission = page.waitForRequest(value => value.method() === 'POST' && value.url().endsWith('/api/ai/generate'))
  await generate.getByRole('button', { name: '开始生成', exact: true }).click()
  expect((await submission).postDataJSON()).toMatchObject({ runId: initial.id, type: 'QUALITY_BRIEF' })
  const brief = page.getByRole('dialog', { name: '本次运行质量分析', exact: true })
  const preview = brief.getByRole('region', { name: '质量简报预览', exact: true })
  await expect(preview.getByLabel('通过率', { exact: true })).toHaveText('50%')
  await expect(preview.getByLabel('统计运行项', { exact: true })).toHaveText('2')
  await expect(preview).toContainText('退款金额需要核实')
  const saved = (await (await request.get(`/api/projects/${project.id}/assets?type=QUALITY_BRIEF`)).json()).items.find((item: Asset) => item.name === '本次运行质量分析') as Asset
  expect(saved.data.runId).toBe(initial.id)
  expect(saved.data.metrics).toMatchObject({ passRatePercent: 50, itemCount: 2, scope: 'RUN', duration: { sampleCount: 0 } })
  await brief.getByRole('textbox', { name: '简报正文', exact: true }).fill('人工结论：需业务核实退款差异。')
  await brief.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(preview).toContainText('人工结论：需业务核实退款差异。')
  await brief.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
  const refine = page.getByRole('dialog', { name: 'AI 优化', exact: true })
  for (const round of [1, 2, 3]) {
    await queue(request, { data: { content: `第 ${round} 轮分析：保留人工待核验结论。` } })
    await refine.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(`第 ${round} 轮：改进表述但保持事实统计`)
    await refine.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(refine.getByRole('alert')).toContainText(`至版本 ${round + 2}`)
    const current = await (await request.get(`/api/projects/${project.id}/assets/${saved.id}`)).json() as Asset
    expect(current.id).toBe(saved.id)
    expect(current.data.metrics).toEqual(saved.data.metrics)
    expect(current.data.runId).toBe(saved.data.runId)
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
  }
  await refine.locator('.arco-drawer-close-btn').click()
  await expect(refine).not.toBeVisible()
  await expect(preview).toContainText('第 3 轮分析')
  await expect(preview.locator('script')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('quality-brief-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('quality-brief-mobile.png') })
  expect(await preview.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await preview.getByRole('button', { name: `查看来源运行 ${initial.id}`, exact: true }).click()
  await expect(page.getByRole('dialog', { name: '执行记录', exact: true }).last().getByTestId('run-status')).toHaveText('失败')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})
