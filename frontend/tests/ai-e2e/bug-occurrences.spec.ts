import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'
import type { DiagnosisOccurrence, RunDetail } from '../../src/api/runs'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const project = await (await request.post('/api/projects', { data: { name: `缺陷历史-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
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
async function create(request: APIRequestContext, project: string, type: string, name: string, data = {}) {
  const result = await request.post(`/api/projects/${project}/assets`, { data: { type, name, data } })
  expect(result.ok()).toBe(true)
  return await result.json() as Asset
}
async function failure(request: APIRequestContext, project: string, target: Asset, notes: string, first: boolean) {
  const submitted = await request.post(`/api/projects/${project}/runs`, { data: { assetId: target.id, idempotencyKey: crypto.randomUUID() } })
  expect(submitted.ok()).toBe(true)
  const accepted = await submitted.json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project}`)).json()).status).toBe('SUCCEEDED')
  const run = await (await request.get(`/api/projects/${project}/runs/${accepted.runId}`)).json() as RunDetail
  const item = run.items[0]!
  expect((await request.post(`/api/projects/${project}/runs/${run.id}/manual-result`, { data: { itemId: item.id, baseVersion: item.manualVersion, status: 'FAILED', notes } })).ok()).toBe(true)
  if (first) expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { title: '账单重复扣款', severity: 'MAJOR', reproduceSteps: '复核账单', expectedResult: '只扣一次', actualResult: '重复扣款', rootCauseAnalysis: '待核实原始流水', fixSuggestion: '按流水逐条核查' } } })).ok()).toBe(true)
  const diagnosis = await request.post(`/api/projects/${project}/runs/${run.id}/diagnose`, { data: { idempotencyKey: crypto.randomUUID() } })
  expect(diagnosis.ok()).toBe(true)
  const job = await diagnosis.json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${job.jobId}?projectId=${project}`)).json()).status).toBe('SUCCEEDED')
  const occurrences = await (await request.get(`/api/projects/${project}/runs/${run.id}/diagnoses`)).json() as { items: DiagnosisOccurrence[] }
  expect(occurrences.items).toHaveLength(1)
  return { run, occurrence: occurrences.items[0]! }
}

test('bug recurrence history preserves human decisions, immutable observations and an unsaved edit while opening the actual source run', async ({ page, request, project }) => {
  const target = await create(request, project.id, 'FUNCTIONAL_CASE', '逐条核对账单')
  const first = await failure(request, project.id, target, '原始流水 A：<script>重复扣款</script>', true)
  const bug = await (await request.get(`/api/projects/${project.id}/assets/${first.occurrence.bugId}`)).json() as Asset
  const human = await (await request.patch(`/api/projects/${project.id}/assets/${bug.id}`, { data: { baseVersion: bug.version, name: '人工维护的缺陷', data: { status: 'CLOSED', rootCauseAnalysis: '人工结论：等待下一版确认' } } })).json() as Asset
  const second = await failure(request, project.id, target, '第二次流水 B：仍然重复扣款', false)
  expect(second.occurrence.bugId).toBe(bug.id)
  expect(await (await request.get(`/api/projects/${project.id}/assets/${bug.id}`)).json()).toEqual(human)
  await page.goto('/bugs')
  await page.getByRole('button', { name: '编辑 人工维护的缺陷', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '人工维护的缺陷', exact: true })
  await drawer.getByRole('textbox', { name: '修复建议', exact: true }).fill('我尚未保存的修复建议')
  await drawer.getByRole('tab', { name: '发生记录', exact: true }).click()
  const history = drawer.getByRole('region', { name: '缺陷发生记录', exact: true })
  await expect(history).toContainText('累计发生 2 次')
  for (const value of [first, second]) {
    const card = history.getByRole('article', { name: `诊断记录 ${value.occurrence.id}`, exact: true })
    await card.getByText('诊断时的原始证据', { exact: true }).click()
    await expect(card.getByTestId('diagnosis-evidence')).toHaveText(JSON.stringify(value.occurrence.evidence, null, 2))
  }
  await expect(history).toContainText('首次创建')
  await expect(history).toContainText('再次发生')
  await expect(history.locator('script')).toHaveCount(0)
  let failNext = true
  await page.route(`**/api/projects/${project.id}/bugs/${bug.id}/occurrences`, async route => {
    if (failNext) { failNext = false; await route.abort('failed') } else await route.continue()
  })
  await history.getByRole('button', { name: '刷新发生记录', exact: true }).click()
  await expect(history.getByRole('alert')).toContainText('无法连接服务')
  await expect(history).toContainText('累计发生 2 次')
  await history.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(history.getByRole('alert')).toHaveCount(0)
  const firstCard = history.getByRole('article', { name: `诊断记录 ${first.occurrence.id}`, exact: true })
  await firstCard.getByRole('button', { name: `查看来源运行 ${first.run.id}`, exact: true }).click()
  const execution = page.getByRole('dialog', { name: '执行记录', exact: true })
  await expect(execution.getByTestId('run-status')).toHaveText('失败')
  await expect(execution.getByRole('textbox', { name: '人工备注', exact: true })).toHaveValue('原始流水 A：<script>重复扣款</script>')
  await execution.locator('.arco-drawer-close-btn').click()
  await drawer.getByRole('tab', { name: '详情与编辑', exact: true }).click()
  await expect(drawer.getByRole('textbox', { name: '修复建议', exact: true })).toHaveValue('我尚未保存的修复建议')
  expect(await (await request.get(`/api/projects/${project.id}/assets/${bug.id}`)).json()).toEqual(human)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('a late occurrence response cannot enter a different bug detail', async ({ page, request, project }) => {
  const target = await create(request, project.id, 'FUNCTIONAL_CASE', '延迟读取的原始用例')
  const first = await failure(request, project.id, target, '仅属于旧缺陷的观察', true)
  const unrelated = await create(request, project.id, 'BUG', '另一个人工缺陷')
  let release: (() => void) | undefined
  const held = new Promise<void>(resolve => { release = resolve })
  let fetched = false
  await page.route(`**/api/projects/${project.id}/bugs/${first.occurrence.bugId}/occurrences`, async route => {
    const response = await route.fetch(); fetched = true
    await held
    try { await route.fulfill({ response }) } catch { /* Closing the old detail aborts its read. */ }
  })
  try {
    await page.goto('/bugs')
    await page.getByRole('button', { name: '编辑 账单重复扣款', exact: true }).click()
    const old = page.getByRole('dialog', { name: '账单重复扣款', exact: true })
    await old.getByRole('tab', { name: '发生记录', exact: true }).click()
    await expect.poll(() => fetched).toBe(true)
    await old.locator('.arco-drawer-close-btn').click()
    await page.getByRole('button', { name: `编辑 ${unrelated.name}`, exact: true }).click()
    const next = page.getByRole('dialog', { name: unrelated.name, exact: true })
    await next.getByRole('tab', { name: '发生记录', exact: true }).click()
    release?.()
    await expect(next.getByRole('region', { name: '缺陷发生记录', exact: true })).toContainText('还没有发生记录')
    await expect(next.getByRole('article', { name: `诊断记录 ${first.occurrence.id}`, exact: true })).toHaveCount(0)
  } finally { release?.() }
})
