import { readFile } from 'node:fs/promises'
import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { RunDetail } from '../../src/api/runs'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use, testInfo) => {
    const project = await (await request.post('/api/projects', { data: { name: `执行验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      if (testInfo.status !== testInfo.expectedStatus) {
        // Keep a failed run's project alive so late worker evidence remains inspectable.
        await testInfo.attach('retained-project', { body: JSON.stringify({ projectId: project.id }), contentType: 'application/json' })
      } else {
        const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
        if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
      }
    }
  },
})
async function create(request: APIRequestContext, projectId: string, type: string, name: string, data = {}, parentId?: string) {
  const response = await request.post(`/api/projects/${projectId}/assets`, { data: { type, name, data, parentId } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}

test('real DDT results, manual conflict recovery and report downloads retain the execution snapshot', async ({ page, request, project }) => {
  const env = await create(request, project.id, 'ENVIRONMENT', '本地验收环境', { baseUrl: 'http://127.0.0.1:8082' })
  const api = await create(request, project.id, 'API_CASE', '订单原始接口', { method: 'POST', path: '/__business/orders', bodyType: 'JSON', body: { qty: '${qty}' }, assertions: [{ type: 'status_code', expected: 201 }, { type: 'jsonpath', path: '$.id', expected: '${qty}' }] })
  const dataset = await create(request, project.id, 'DATASET', '两行订单', { columns: ['qty'], rows: [{ qty: 1 }, { qty: 2 }] })
  const manual = await create(request, project.id, 'FUNCTIONAL_CASE', '人工确认账单', { precondition: '账单已完成结算' })
  const manualStep = await create(request, project.id, 'FUNCTIONAL_STEP', '核对账单金额', { step: '打开账单并核对明细', expected: '账单合计等于明细之和' }, manual.id)
  const plan = await create(request, project.id, 'TEST_PLAN', '混合验收计划', { environmentId: env.id, diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '接口数据行', { targetId: api.id, datasetId: dataset.id }, plan.id)
  await create(request, project.id, 'PLAN_ITEM', '人工项目', { targetId: manual.id, executionMode: 'MANUAL' }, plan.id)
  await page.goto('/plans')
  await page.getByRole('button', { name: '执行 混合验收计划', exact: true }).click()
  const launch = page.getByRole('dialog', { name: '执行测试', exact: true })
  const response = page.waitForResponse(result => result.request().method() === 'POST' && result.url().endsWith('/runs'))
  await launch.getByRole('button', { name: '开始执行', exact: true }).click()
  const accepted = await (await response).json()
  const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
  await expect(drawer.getByTestId('run-status')).toHaveText('待人工')
  await expect(drawer.getByLabel('运行项总数')).toHaveText('3')
  await expect(drawer.getByLabel('去重用例数')).toHaveText('2')
  await expect(drawer.getByLabel('数据行数')).toHaveText('2')
  const detail = await (await request.get(`/api/projects/${project.id}/runs/${accepted.runId}`)).json()
  expect(detail.summary).toEqual({ total: 3, caseCount: 2, dataRows: 2, counts: { PASSED: 2, MANUAL_PENDING: 1 } })
  const item = detail.items.find((entry: { assetId: string }) => entry.assetId === manual.id)
  const form = drawer.getByRole('form', { name: '人工结果 人工确认账单', exact: true })
  const instructions = drawer.getByRole('region', { name: '执行说明 人工确认账单', exact: true })
  await expect(instructions).toContainText('账单已完成结算')
  await expect(instructions.getByRole('cell', { name: '打开账单并核对明细', exact: true })).toBeVisible()
  await expect(instructions.getByRole('cell', { name: '账单合计等于明细之和', exact: true })).toBeVisible()
  await form.getByLabel('人工判定', { exact: true }).selectOption('PASSED')
  await form.getByLabel('人工备注', { exact: true }).fill('我已经核对账单')
  expect((await request.post(`/api/projects/${project.id}/runs/${accepted.runId}/manual-result`, { data: { itemId: item.id, baseVersion: item.manualVersion, status: 'FAILED', notes: '另一位同事发现问题' } })).ok()).toBe(true)
  const conflicted = page.waitForResponse(result => result.url().endsWith('/manual-result'))
  await form.getByRole('button', { name: '保存人工结果', exact: true }).click()
  expect((await conflicted).status()).toBe(409)
  await expect(form.getByLabel('人工备注', { exact: true })).toHaveValue('我已经核对账单')
  await expect(form.getByText('另一位同事发现问题', { exact: true })).toBeVisible()
  await form.getByRole('button', { name: '保留我的记录并使用最新版本', exact: true }).click()
  await form.getByRole('button', { name: '保存人工结果', exact: true }).click()
  await expect(drawer.getByTestId('run-status')).toHaveText('通过')
  await request.patch(`/api/projects/${project.id}/assets/${api.id}`, { data: { baseVersion: api.version, name: '人工改名后的接口', data: { path: '/not-the-old-path' } } })
  expect((await request.patch(`/api/projects/${project.id}/assets/${manual.id}`, { data: { baseVersion: manual.version, data: { precondition: '新版本前置条件' } } })).ok()).toBe(true)
  expect((await request.patch(`/api/projects/${project.id}/assets/${manualStep.id}`, { data: { baseVersion: manualStep.version, data: { step: '新版本测试步骤', expected: '新版本预期结果' } } })).ok()).toBe(true)
  await drawer.getByRole('button', { name: '刷新运行', exact: true }).click()
  await expect(instructions).toContainText('账单已完成结算')
  await expect(instructions).toContainText('账单合计等于明细之和')
  await expect(instructions).not.toContainText('新版本')
  await drawer.getByText('运行时资产快照', { exact: true }).click()
  await expect(drawer.getByTestId('run-snapshot')).toContainText('订单原始接口')
  await expect(drawer.getByTestId('run-snapshot')).not.toContainText('人工改名后的接口')
  await drawer.getByLabel('报告格式', { exact: true }).selectOption('json')
  const downloading = page.waitForEvent('download')
  await drawer.getByRole('button', { name: '下载运行报告', exact: true }).click()
  const download = await downloading
  const saved = await download.path()
  expect(saved).toBeTruthy()
  const report = JSON.parse(await readFile(saved!, 'utf8'))
  expect(JSON.stringify(report)).toContain('我已经核对账单')
  expect(JSON.stringify(report)).toContain('订单原始接口')
  expect(JSON.stringify(report)).not.toContain('人工改名后的接口')
  await page.reload()
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  await drawer.getByRole('button', { name: '查看运行 混合验收计划', exact: true }).click()
  await expect(drawer.getByTestId('run-status')).toHaveText('通过')
  await expect(instructions).toContainText('打开账单并核对明细')
  await expect(instructions).not.toContainText('新版本')
})

test('browser failure evidence remains downloadable and a delayed old-project run cannot enter the next project', async ({ page, request, project }, testInfo) => {
  const scenarioTimeoutMs = 120000
  test.setTimeout(scenarioTimeoutMs + 90000)
  const env = await create(request, project.id, 'ENVIRONMENT', 'Web 验收环境', { baseUrl: 'http://127.0.0.1:8082', webUrl: 'http://127.0.0.1:8082/__business/page' })
  const ui = await create(request, project.id, 'UI_SCENARIO', '有失败证据的 UI', { baseUrl: 'http://127.0.0.1:8082/__business/page', timeoutMs: scenarioTimeoutMs })
  const navigation = await create(request, project.id, 'UI_STEP', '打开订单页', { action: 'navigate', url: 'http://127.0.0.1:8082/__business/page' }, ui.id)
  const assertion = await create(request, project.id, 'UI_STEP', '核对错误标题', { action: 'assertText', selector: '#title', expected: '不可能的标题', timeoutMs: 1000 }, ui.id)
  const plan = await create(request, project.id, 'TEST_PLAN', '浏览器证据计划', { environmentId: env.id, diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', 'UI 项目', { targetId: ui.id }, plan.id)
  const response = await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, idempotencyKey: crypto.randomUUID() } })
  expect(response.ok()).toBe(true)
  const submitted = await response.json()
  // Include the existing worker budget and result-persistence time, then check the actual outcome.
  await expect.poll(async () => (await (await request.get(`/api/jobs/${submitted.jobId}?projectId=${project.id}`)).json()).status, { timeout: scenarioTimeoutMs + 30000 }).toMatch(/^(SUCCEEDED|FAILED|CANCELLED|INTERRUPTED)$/)
  const job = await (await request.get(`/api/jobs/${submitted.jobId}?projectId=${project.id}`)).json()
  const detail = await (await request.get(`/api/projects/${project.id}/runs/${submitted.runId}`)).json() as RunDetail
  await testInfo.attach('completed-browser-run', { body: JSON.stringify({ job, detail }), contentType: 'application/json' })
  expect(job.status).toBe('SUCCEEDED')
  expect(detail.status).toBe('FAILED')
  expect(detail.items).toHaveLength(1)
  expect(detail.items[0]!.status).toBe('FAILED')
  expect(detail.items[0]!.steps.map(step => ({ assetId: step.assetId, status: step.status }))).toEqual([
    { assetId: navigation.id, status: 'PASSED' }, { assetId: assertion.id, status: 'FAILED' },
  ])
  expect(detail.items[0]!.steps[1]!.result.assertions).toEqual([
    expect.objectContaining({ type: 'assertText', path: '#title', expected: '不可能的标题', passed: false }),
  ])
  await page.goto('/plans')
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
  await drawer.getByRole('button', { name: '查看运行 浏览器证据计划', exact: true }).click()
  await expect(drawer.getByTestId('run-status')).toHaveText('失败')
  await drawer.getByText('步骤证据 · 核对错误标题', { exact: true }).click()
  const downloading = page.waitForEvent('download')
  await drawer.getByRole('button', { name: /下载附件/ }).first().click()
  const download = await downloading
  const saved = await download.path()
  expect(saved).toBeTruthy()
  const screenshot = await readFile(saved!)
  expect(screenshot.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a')
  await testInfo.attach('downloaded-browser-failure', { body: screenshot, contentType: 'image/png' })
  await page.screenshot({ path: testInfo.outputPath('browser-evidence-drawer.png'), fullPage: false })

  const other = await (await request.post('/api/projects', { data: { name: `另一执行项目-${crypto.randomUUID().slice(0, 6)}`, data: {} } })).json() as Asset
  let release: (() => void) | undefined
  try {
    await page.reload()
    await page.getByRole('button', { name: '执行记录', exact: true }).click()
    await drawer.getByRole('button', { name: '查看运行 浏览器证据计划', exact: true }).click()
    const hold = new Promise<void>(resolve => { release = resolve })
    let reached: (() => void) | undefined
    const entered = new Promise<void>(resolve => { reached = resolve })
    await page.route(`**/api/projects/${project.id}/runs/${submitted.runId}`, async route => { const response = await route.fetch(); reached?.(); await hold; try { await route.fulfill({ response }) } catch { /* The project switch aborts the old detail request. */ } })
    await drawer.getByRole('button', { name: '刷新运行', exact: true }).click()
    await entered
    await drawer.locator('.arco-drawer-close-btn').click()
    await page.getByLabel('当前项目', { exact: true }).click()
    await page.getByText(other.name, { exact: true }).click()
    release?.()
    await expect(drawer).toBeHidden()
    await page.getByRole('button', { name: '执行记录', exact: true }).click()
    await expect(drawer.getByText('还没有执行记录', { exact: true })).toBeVisible()
    await expect(drawer.getByText('浏览器证据计划', { exact: true })).toHaveCount(0)
  } finally {
    release?.()
    await request.delete(`/api/projects/${other.id}/assets/${other.id}?baseVersion=${other.version}`)
  }
})
