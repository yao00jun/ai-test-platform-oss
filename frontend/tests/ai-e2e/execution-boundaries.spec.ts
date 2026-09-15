import { expect, test as base, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'
import type { DiagnosisOccurrence, RunDetail, RunSubmission, ValidationResult } from '../../src/api/runs'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const response = await request.post('/api/projects', { data: { name: `执行边界-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
    expect(response.ok()).toBe(true)
    const project = await response.json() as Asset
    const pageErrors: string[] = []
    page.on('pageerror', error => pageErrors.push(error.message))
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
      expect(pageErrors).toEqual([])
    }
  },
})

async function create(request: APIRequestContext, projectId: string, type: string, name: string, data = {}, parentId?: string) {
  const response = await request.post(`/api/projects/${projectId}/assets`, { data: { type, name, data, parentId } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}
async function submit(request: APIRequestContext, projectId: string, assetId: string) {
  const response = await request.post(`/api/projects/${projectId}/runs`, { data: { assetId, idempotencyKey: crypto.randomUUID() } })
  expect(response.ok()).toBe(true)
  return await response.json() as RunSubmission
}
async function getRun(request: APIRequestContext, projectId: string, runId: string) {
  const response = await request.get(`/api/projects/${projectId}/runs/${runId}`)
  expect(response.ok()).toBe(true)
  return await response.json() as RunDetail
}
async function cancel(request: APIRequestContext, projectId: string, jobId: string) {
  expect((await request.post(`/api/jobs/${jobId}/cancel`, { data: { projectId } })).ok()).toBe(true)
}

// The real WAIT executor checkpoints cancellation every 100 ms. Occupying the
// fixture's eight job slots makes the queued-cancellation contract reproducible.
async function queuedCancellation(page: Page, request: APIRequestContext, project: Asset, failRead: boolean) {
  const waiting = await create(request, project.id, 'SCENARIO', '占用执行槽的有界等待')
  await create(request, project.id, 'SCENARIO_STEP', '等待取消', { stepType: 'WAIT', waitMs: 60000 }, waiting.id)
  const jobs: RunSubmission[] = []
  try {
    for (let index = 0; index < 8; index++) jobs.push(await submit(request, project.id, waiting.id))
    await expect.poll(async () => Promise.all(jobs.map(async job => (await (await request.get(`/api/jobs/${job.jobId}?projectId=${project.id}`)).json()).status))).toEqual(Array(8).fill('RUNNING'))
    const probeAsset = await create(request, project.id, 'FUNCTIONAL_CASE', '取消时序探针')
    const targetAsset = await create(request, project.id, 'FUNCTIONAL_CASE', '等待取消的运行')
    const probe = await submit(request, project.id, probeAsset.id)
    const target = await submit(request, project.id, targetAsset.id)
    jobs.push(probe, target)
    expect((await getRun(request, project.id, target.runId)).status).toBe('QUEUED')

    let summaryReads = 0
    page.on('request', value => { if (value.url().endsWith(`/api/projects/${project.id}/summary`)) summaryReads++ })
    await page.goto('/')
    const dashboardRun = page.getByRole('row').filter({ has: page.getByRole('button', { name: '查看运行 等待取消的运行', exact: true }) })
    const dashboardStatus = dashboardRun.getByRole('cell').nth(1)
    await expect(dashboardStatus).toHaveText('排队中')
    await dashboardRun.getByRole('button').click()
    const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
    await expect(drawer.getByTestId('run-status')).toHaveText('排队中')
    await expect(drawer.getByRole('button', { name: '取消此次运行', exact: true })).toBeVisible()

    // Observe a real reconciliation tick before cancelling the target, leaving
    // a full reconciliation interval for the job/run distinction to be visible.
    await cancel(request, project.id, probe.jobId)
    await expect.poll(async () => (await getRun(request, project.id, probe.runId)).status, { intervals: [50, 100, 100, 100] }).toBe('CANCELLED')
    let interruptedReads = 0, readOutage = failRead
    if (failRead) {
      await page.route(`**/api/projects/${project.id}/runs/${target.runId}`, async route => {
        const response = await route.fetch()
        const job = await (await request.get(`/api/jobs/${target.jobId}?projectId=${project.id}`)).json()
        if (readOutage && job.status === 'CANCELLED') { interruptedReads++; await route.abort('failed') }
        else await route.fulfill({ response })
      })
    }
    await drawer.getByRole('button', { name: '取消此次运行', exact: true }).click()
    await expect(drawer.locator('.run-job')).toContainText('调度任务：已取消')
    if (failRead) {
      // Cancellation monitoring can supersede a read. Keep the brief outage
      // active until the current read fails, then restore real responses.
      await expect(drawer.getByRole('alert')).toContainText('无法连接服务')
      await expect(drawer.getByTestId('run-status')).toHaveText('排队中')
      expect(interruptedReads).toBeGreaterThan(0)
      readOutage = false
    }
    await expect.poll(async () => (await getRun(request, project.id, target.runId)).status).toBe('CANCELLED')
    // No Refresh click: a transient read must not strand the retained QUEUED run.
    await expect(drawer.getByTestId('run-status')).toHaveText('已取消')
    if (failRead) await expect(drawer.getByRole('alert')).toHaveCount(0)
    else {
      await expect(dashboardStatus).toHaveText('已取消')
      const settledReads = summaryReads
      for (let index = 0; index < 2; index++) {
        const refreshed = page.waitForResponse(value => value.url().endsWith(`/runs/${target.runId}/diagnoses`))
        await drawer.getByRole('button', { name: '刷新运行', exact: true }).click()
        await (await refreshed).finished()
        await expect(drawer.getByRole('button', { name: '刷新运行', exact: true })).not.toHaveClass(/arco-btn-loading/)
      }
      expect(summaryReads).toBe(settledReads)
    }
    await drawer.locator('.arco-drawer-close-btn').click()
    await expect(drawer).toBeHidden()
    if (!failRead) await expect(dashboardStatus).toHaveText('已取消')
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  } finally {
    for (const job of jobs) await cancel(request, project.id, job.jobId)
  }
}

test('a transient post-cancellation run read recovers without a manual refresh', async ({ page, request, project }) => {
  await queuedCancellation(page, request, project, true)
})

test('dashboard follows persisted queued cancellation and deduplicates unchanged reads', async ({ page, request, project }) => {
  await queuedCancellation(page, request, project, false)
})

test('diagnosis occurrences retain their manual observation, row and step evidence after later edits', async ({ page, request, project }, testInfo) => {
  const env = await create(request, project.id, 'ENVIRONMENT', '诊断验收环境', { baseUrl: 'http://127.0.0.1:8082' })
  const api = await create(request, project.id, 'API_CASE', '诊断原始订单接口', { method: 'POST', path: '/__business/orders', bodyType: 'JSON', body: { qty: '${qty}' }, assertions: [{ type: 'status_code', expected: 500 }] })
  const data = await create(request, project.id, 'DATASET', '两行失败证据', { columns: ['qty'], rows: [{ qty: 1 }, { qty: 2 }] })
  const manual = await create(request, project.id, 'FUNCTIONAL_CASE', '人工账单诊断')
  const plan = await create(request, project.id, 'TEST_PLAN', '原始证据验收', { environmentId: env.id, diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '订单数据行', { targetId: api.id, datasetId: data.id }, plan.id)
  await create(request, project.id, 'PLAN_ITEM', '人工核对', { targetId: manual.id, executionMode: 'MANUAL' }, plan.id)
  const accepted = await submit(request, project.id, plan.id)
  await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await page.goto('/plans')
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
  await drawer.getByRole('button', { name: '查看运行 原始证据验收', exact: true }).click()
  await expect(drawer.locator('.run-job')).toContainText('调度任务：已完成')
  await expect(drawer.getByRole('button', { name: '刷新运行', exact: true })).not.toHaveClass(/arco-btn-loading/)
  const form = drawer.getByRole('form', { name: '人工结果 人工账单诊断', exact: true })
  const original = '原始观察 A：账单多扣 10 元，页面显示 <script>旧记录</script>'
  const revised = '后续观察 B：账单已修复并重新核对'
  await form.getByLabel('人工判定', { exact: true }).selectOption('FAILED')
  await form.getByLabel('人工备注', { exact: true }).fill(original)
  const originalSaved = page.waitForResponse(value => value.url().endsWith('/manual-result'))
  await form.getByRole('button', { name: '保存人工结果', exact: true }).click()
  expect((await originalSaved).ok()).toBe(true)
  expect((await getRun(request, project.id, accepted.runId)).items.find(item => item.assetId === manual.id)?.notes).toBe(original)
  const diagnosis = { title: '诊断时保存的结论', severity: 'MAJOR', reproduceSteps: '检查原始记录', expectedResult: '记录一致', actualResult: '记录不一致', rootCauseAnalysis: '待核实原始失败', fixSuggestion: '按原始记录复核' }
  for (let index = 0; index < 2; index++) expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: diagnosis } })).ok()).toBe(true)
  const diagnosing = page.waitForResponse(value => value.url().endsWith(`/runs/${accepted.runId}/diagnose`))
  await drawer.getByRole('button', { name: 'AI 诊断失败并记录缺陷', exact: true }).click()
  const diagnosisJob = await (await diagnosing).json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${diagnosisJob.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await expect(drawer.getByText('诊断与缺陷记录 · 3', { exact: true })).toBeVisible()
  const occurrences = await (await request.get(`/api/projects/${project.id}/runs/${accepted.runId}/diagnoses`)).json() as { items: DiagnosisOccurrence[] }
  expect(occurrences.items).toHaveLength(3)

  await form.getByLabel('人工判定', { exact: true }).selectOption('PASSED')
  await form.getByLabel('人工备注', { exact: true }).fill(revised)
  const saved = page.waitForResponse(value => value.url().endsWith('/manual-result'))
  await form.getByRole('button', { name: '保存人工结果', exact: true }).click()
  expect((await saved).ok()).toBe(true)
  await drawer.getByRole('button', { name: '刷新运行', exact: true }).click()
  await expect(form.getByLabel('人工备注', { exact: true })).toHaveValue(revised)
  const currentRun = await getRun(request, project.id, accepted.runId)
  expect(currentRun.items.find(item => item.assetId === manual.id)?.notes).toBe(revised)
  await drawer.getByText('诊断与缺陷记录 · 3', { exact: true }).click()
  for (const occurrence of occurrences.items) {
    const article = drawer.getByRole('article', { name: `诊断记录 ${occurrence.id}`, exact: true })
    await article.getByText('诊断时的原始证据', { exact: true }).click()
    const evidence = article.getByTestId('diagnosis-evidence')
    await expect(evidence).toHaveText(JSON.stringify(occurrence.evidence, null, 2))
    await expect(evidence).not.toContainText(revised)
  }
  const allEvidence = drawer.getByTestId('diagnosis-evidence')
  await expect(allEvidence.filter({ hasText: original })).toHaveCount(1)
  const renderedEvidence = (await allEvidence.allTextContents()).map(value => JSON.parse(value))
  expect(renderedEvidence).toEqual(expect.arrayContaining([
    expect.objectContaining({ caseId: api.id, rowIndex: 0, stepName: '诊断原始订单接口' }),
    expect.objectContaining({ caseId: api.id, rowIndex: 1, failedAssertions: expect.arrayContaining([expect.objectContaining({ expected: 500, actual: 201, passed: false })]) }),
  ]))
  await expect(drawer.locator('.run-diagnoses script')).toHaveCount(0)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await allEvidence.filter({ hasText: original }).scrollIntoViewIfNeeded()
  await testInfo.attach('execution-evidence-desktop', { body: await page.screenshot({ path: testInfo.outputPath('execution-evidence-desktop.png') }), contentType: 'image/png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await allEvidence.filter({ hasText: original }).scrollIntoViewIfNeeded()
  await testInfo.attach('execution-evidence-mobile', { body: await page.screenshot({ path: testInfo.outputPath('execution-evidence-mobile.png') }), contentType: 'image/png' })
})

test('validation names the first and last data rows without changing persisted run row labels', async ({ page, request, project }) => {
  const env = await create(request, project.id, 'ENVIRONMENT', '行号验收环境', { baseUrl: 'http://127.0.0.1:8082' })
  const api = await create(request, project.id, 'API_CASE', '检查数据行的订单接口', { method: 'POST', path: '/__business/orders', bodyType: 'JSON', body: { qty: '${qty}' }, assertions: [{ type: 'status_code', expected: 201 }] })
  const data = await create(request, project.id, 'DATASET', '首尾行缺变量', { columns: ['qty'], rows: [{ qty: '${firstMissing}' }, { qty: 2 }, { qty: '${lastMissing}' }] })
  const plan = await create(request, project.id, 'TEST_PLAN', '数据行号验收', { environmentId: env.id, diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '逐行订单', { targetId: api.id, datasetId: data.id }, plan.id)
  await page.goto('/plans')
  await page.getByRole('button', { name: '执行 数据行号验收', exact: true }).click()
  const launch = page.getByRole('dialog', { name: '执行测试', exact: true })
  const checked = page.waitForResponse(value => value.url().endsWith('/validate-execution'))
  await launch.getByRole('button', { name: '检查变量与引用', exact: true }).click()
  const validation = await (await checked).json() as ValidationResult
  expect(validation.errors.map(issue => ({ rowIndex: issue.rowIndex, variable: issue.variable }))).toEqual([{ rowIndex: 1, variable: 'firstMissing' }, { rowIndex: 3, variable: 'lastMissing' }])
  await expect.soft(launch.locator('.run-validation p').filter({ hasText: 'firstMissing' })).toContainText('数据行 1：')
  await expect.soft(launch.locator('.run-validation p').filter({ hasText: 'lastMissing' })).toContainText('数据行 3：')

  expect((await request.patch(`/api/projects/${project.id}/assets/${data.id}`, { data: { baseVersion: data.version, data: { columns: ['qty'], rows: [{ qty: 1 }, { qty: 2 }, { qty: 3 }] } } })).ok()).toBe(true)
  const submitted = page.waitForResponse(value => value.request().method() === 'POST' && value.url().endsWith('/runs'))
  await launch.getByRole('button', { name: '开始执行', exact: true }).click()
  const accepted = await (await submitted).json() as RunSubmission
  const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
  await expect(drawer.getByTestId('run-status')).toHaveText('通过')
  expect((await getRun(request, project.id, accepted.runId)).items.map(item => item.rowIndex)).toEqual([0, 1, 2])
  await expect(drawer.locator('.run-item').nth(0).locator('header')).toContainText('数据行 1')
  await expect(drawer.locator('.run-item').nth(2).locator('header')).toContainText('数据行 3')
})

test('refresh preserves a manual draft and its original CAS version after a concurrent save', async ({ page, request, project }) => {
  const manual = await create(request, project.id, 'FUNCTIONAL_CASE', '刷新期间的人工草稿')
  const accepted = await submit(request, project.id, manual.id)
  await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await page.goto('/cases')
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行记录', exact: true })
  await drawer.getByRole('button', { name: '查看运行 刷新期间的人工草稿', exact: true }).click()
  await expect(drawer.locator('.run-job')).toContainText('调度任务：已完成')
  await expect(drawer.getByRole('button', { name: '刷新运行', exact: true })).not.toHaveClass(/arco-btn-loading/)
  const original = (await getRun(request, project.id, accepted.runId)).items[0]!
  const form = drawer.getByRole('form', { name: '人工结果 刷新期间的人工草稿', exact: true })
  const draft = '我的未保存观察：账单内容已逐条核对'
  const colleague = '同事保存的失败记录'
  await form.getByLabel('人工判定', { exact: true }).selectOption('PASSED')
  await form.getByLabel('人工备注', { exact: true }).fill(draft)
  expect((await request.post(`/api/projects/${project.id}/runs/${accepted.runId}/manual-result`, { data: { itemId: original.id, baseVersion: original.manualVersion, status: 'FAILED', notes: colleague } })).ok()).toBe(true)
  const refreshed = page.waitForResponse(value => value.url().endsWith(`/runs/${accepted.runId}`))
  await drawer.getByRole('button', { name: '刷新运行', exact: true }).click()
  expect((await refreshed).ok()).toBe(true)
  await expect(drawer.getByTestId('run-status')).toHaveText('失败')
  await expect.soft(form.getByLabel('人工判定', { exact: true })).toHaveValue('PASSED', { timeout: 3000 })
  await expect.soft(form.getByLabel('人工备注', { exact: true })).toHaveValue(draft, { timeout: 3000 })
  await expect.soft(form.getByText(`记录版本 ${original.manualVersion}`, { exact: true })).toBeVisible({ timeout: 3000 })

  const saving = page.waitForResponse(value => value.url().endsWith('/manual-result'))
  await form.getByRole('button', { name: '保存人工结果', exact: true }).click()
  const conflict = await saving
  expect(conflict.request().postDataJSON().baseVersion).toBe(original.manualVersion)
  expect(conflict.status()).toBe(409)
  await expect(form.getByLabel('人工备注', { exact: true })).toHaveValue(draft)
  await expect(form.getByText(colleague, { exact: true })).toBeVisible()
  expect((await getRun(request, project.id, accepted.runId)).items[0]!.notes).toBe(colleague)
})
