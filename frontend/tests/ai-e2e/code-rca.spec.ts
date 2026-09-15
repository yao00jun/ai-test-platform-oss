import { expect, test, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises'
import path from 'node:path'
import { tmpdir } from 'node:os'
import type { DiagnosisOccurrence } from '../../src/api/runs'

async function create(request: APIRequestContext, projectId: string, name: string, data = {}) {
  const response = await request.post(`/api/projects/${projectId}/assets`, { data: { type: 'BUG', name, data } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}
async function removeSources(root: string) {
  if (path.dirname(root) !== path.resolve(tmpdir()) || !path.basename(root).startsWith('aitest-code-rca-')) throw new Error('Unexpected fixture cleanup target')
  await rm(root, { recursive: true, force: true })
}

test('manual legacy RCA remains usable without source and preserves drafts, CAS and independent human evaluation', async ({ page, request }) => {
  const project = await (await request.post('/api/projects', { data: { name: `人工源码诊断-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  try {
    const bug = await create(request, project.id, '人工维护的代码结论', { rootCauseAnalysis: '原有人工结论' })
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/bugs')
    await page.getByRole('button', { name: `编辑 ${bug.name}`, exact: true }).click()
    const drawer = page.getByRole('dialog', { name: bug.name, exact: true })
    await drawer.getByRole('textbox', { name: '修复建议', exact: true }).fill('尚未保存的普通修复建议')
    await drawer.getByRole('tab', { name: '源码诊断', exact: true }).click()
    const panel = drawer.getByRole('region', { name: '源码诊断', exact: true })
    await expect(panel).toContainText('没有可定位的源码证据')
    await panel.getByLabel('代码根因推测', { exact: true }).fill('人工分析：继续收集现场')
    await drawer.getByRole('tab', { name: '发生记录', exact: true }).click()
    await drawer.getByRole('tab', { name: '源码诊断', exact: true }).click()
    await expect(panel.getByLabel('代码根因推测', { exact: true })).toHaveValue('人工分析：继续收集现场')
    const remote = await request.patch(`/api/projects/${project.id}/assets/${bug.id}`, { data: { baseVersion: bug.version, data: { status: 'CLOSED' } } })
    expect(remote.ok()).toBe(true)
    await drawer.getByRole('button', { name: '刷新当前记录', exact: true }).click()
    await expect(panel.getByLabel('代码根因推测', { exact: true })).toHaveValue('人工分析：继续收集现场')
    await panel.getByRole('button', { name: '保存代码诊断', exact: true }).click()
    await expect(panel.getByRole('alert')).toBeVisible()
    await panel.getByRole('button', { name: '保留代码修改并使用最新版本', exact: true }).click()
    await panel.getByRole('button', { name: '保存代码诊断', exact: true }).click()
    await expect(panel.getByRole('status').filter({ hasText: '代码诊断已保存' })).toBeVisible()
    const saved = await (await request.get(`/api/projects/${project.id}/assets/${bug.id}`)).json() as Asset
    expect(saved.data).toMatchObject({ status: 'CLOSED', rootCauseAnalysis: '原有人工结论', codeDiagnosis: { root_cause: '人工分析：继续收集现场', confidence: null, is_regression: null } })
    await panel.getByLabel('RCA 人工评价', { exact: true }).selectOption('PARTIAL')
    await panel.getByLabel('退化缺陷人工确认', { exact: true }).selectOption('NOT_REGRESSION')
    await panel.getByLabel('评价备注', { exact: true }).fill('待补现场；尚无退化证据')
    const submissions: Record<string, unknown>[] = [], acceptedIds: string[] = []
    await page.route(`**/api/projects/${project.id}/bugs/${bug.id}/rca-evaluations`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      submissions.push(route.request().postDataJSON())
      const response = await route.fetch(); expect(response.ok()).toBe(true); acceptedIds.push((await response.json()).id)
      if (submissions.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    await panel.getByRole('button', { name: '保存人工评价', exact: true }).click()
    await expect(panel.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    await panel.getByRole('button', { name: '保存人工评价', exact: true }).click()
    await expect(panel.getByRole('status').filter({ hasText: '人工评价已保存' })).toBeVisible()
    expect(submissions[1]).toEqual(submissions[0]); expect(acceptedIds[1]).toBe(acceptedIds[0])
    expect(submissions[0]).not.toHaveProperty('actor')
    await panel.getByLabel('代码根因推测', { exact: true }).fill('第二版人工推测')
    await panel.getByRole('button', { name: '保存代码诊断', exact: true }).click()
    await expect(panel.getByTestId('rca-current-evaluation')).toContainText('未评价')
    await expect(panel.getByRole('article', { name: `人工评价 ${acceptedIds[0]}`, exact: true })).toContainText('部分正确')
    await drawer.getByRole('tab', { name: '详情与编辑', exact: true }).click()
    await expect(drawer.getByRole('textbox', { name: '修复建议', exact: true })).toHaveValue('尚未保存的普通修复建议')
    expect(errors).toEqual([])
  } finally {
    const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
  }
})

test('actual HTTP stack source and safe patches survive two code-only feedback rounds and leave human text and other bugs intact', async ({ page, request, context }, testInfo) => {
  test.setTimeout(120000)
  await request.post('http://127.0.0.1:8082/__fixture/reset')
  const root = await mkdtemp(path.join(tmpdir(), 'aitest-code-rca-'))
  const source = 'package shop;\nclass OrderService {\n  String refund(String id) {\n    if (id == null) throw new IllegalArgumentException("missing id");\n    return id.trim();\n  }\n}\n'
  await writeFile(path.join(root, 'OrderService.java'), source)
  await mkdir(path.join(root, 'other'))
  await writeFile(path.join(root, 'other', 'OrderService.java'), source.replace('package shop;', 'package unrelated;').replace('missing id', 'wrong class'))
  const project = await (await request.post('/api/projects', { data: { name: `堆栈源码诊断-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  try {
    const sourceResponse = await request.post(`/api/projects/${project.id}/source-analyses`, { data: { backendRepoPath: root, idempotencyKey: crypto.randomUUID() } })
    expect(sourceResponse.ok()).toBe(true)
    const analysis = await sourceResponse.json()
    await expect.poll(async () => (await (await request.get(`/api/jobs/${analysis.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    const env = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'ENVIRONMENT', name: '本地堆栈测试', data: { baseUrl: 'http://127.0.0.1:8082' } } })).json() as Asset
    const target = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'API_CASE', name: '真实失败接口', data: { sourceSnapshotId: analysis.analysisId, method: 'GET', path: '/__business/rca-error', assertions: [{ type: 'status_code', expected: 200 }] } } })).json() as Asset
    const planResponse = await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'TEST_PLAN', name: '固定堆栈诊断计划', data: { diagnoseFailures: false } } })
    expect(planResponse.ok()).toBe(true)
    const plan = await planResponse.json() as Asset
    expect((await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'PLAN_ITEM', parentId: plan.id, name: '退款接口', data: { targetId: target.id } } })).ok()).toBe(true)
    const runResponse = await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: plan.id, environmentId: env.id, idempotencyKey: crypto.randomUUID() } })
    expect(runResponse.ok()).toBe(true)
    const run = await runResponse.json()
    await expect.poll(async () => (await (await request.get(`/api/jobs/${run.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    await writeFile(path.join(root, 'OrderService.java'), source.replace('missing id', 'disk was changed after execution'))
    const patch = '--- a/OrderService.java\n+++ b/OrderService.java\n@@ -3,3 +3,3 @@\n   String refund(String id) {\n-    if (id == null) throw new IllegalArgumentException("missing id");\n+    if (id == null) return "pending review";\n     return id.trim();\n'
    const code = { formatVersion: 'aitest.code-rca/v1', root_cause: '待核实：缺少 id 的错误处理', affected_code_path: 'OrderService.java:4', suggested_fix: patch, is_regression: null, confidence: 0.6 }
    const diagnosis = { title: '空 id 产生异常', severity: 'MAJOR', reproduceSteps: '请求退款', expectedResult: '明确的参数错误', actualResult: 'HTTP 500', rootCauseAnalysis: '初始推测', fixSuggestion: '核对堆栈与参数校验', codeDiagnosis: code }
    expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: diagnosis } })).ok()).toBe(true)
    const submission = await request.post(`/api/projects/${project.id}/runs/${run.runId}/diagnose`, { data: { idempotencyKey: crypto.randomUUID() } })
    expect(submission.ok()).toBe(true)
    const accepted = await submission.json()
    await expect.poll(async () => (await (await request.get(`/api/jobs/${accepted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    const occurrence = (await (await request.get(`/api/projects/${project.id}/runs/${run.runId}/diagnoses`)).json()).items[0] as DiagnosisOccurrence
    let bug = await (await request.get(`/api/projects/${project.id}/assets/${occurrence.bugId}`)).json() as Asset
    bug = await (await request.patch(`/api/projects/${project.id}/assets/${bug.id}`, { data: { baseVersion: bug.version, data: { status: 'CLOSED', rootCauseAnalysis: '人工结论保持原样', suggestion: '人工修复建议保持原样' } } })).json() as Asset
    const other = await create(request, project.id, '另一个不相关缺陷', { rootCauseAnalysis: '另一条人工结论' })
    const original = JSON.parse(JSON.stringify(bug)) as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto('/bugs'); await page.getByRole('button', { name: `编辑 ${bug.name}`, exact: true }).click()
    const drawer = page.getByRole('dialog', { name: bug.name, exact: true })
    await drawer.getByRole('tab', { name: '源码诊断', exact: true }).click()
    const panel = drawer.getByRole('region', { name: '源码诊断', exact: true })
    const evidence = panel.getByRole('region', { name: '固定源码证据', exact: true })
    await expect(evidence).toContainText('OrderService.java:4')
    await expect(evidence).toContainText('missing id')
    await expect(evidence).not.toContainText('disk was changed after execution')
    await expect(evidence).not.toContainText('wrong class')
    await expect(panel.getByTestId('rca-patch')).toHaveText(patch)
    await panel.getByRole('button', { name: '复制补丁', exact: true }).click()
    await expect(panel.getByRole('status').filter({ hasText: '补丁已复制' })).toBeVisible()
    // Windows text clipboard normalizes LF to CRLF; downloaded patch bytes stay exact.
    expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(patch)
    const downloading = page.waitForEvent('download'); await panel.getByRole('button', { name: '下载补丁', exact: true }).click()
    const file = await downloading, filename = testInfo.outputPath(file.suggestedFilename()); await file.saveAs(filename)
    expect(await readFile(filename, 'utf8')).toBe(patch)
    const inputs: Record<string, unknown>[] = []
    page.on('request', incoming => { if (incoming.url().endsWith('/ai/refine-item') && incoming.method() === 'POST') inputs.push(incoming.postDataJSON()) })
    for (let round = 1; round <= 2; round++) {
      const next = { ...code, root_cause: `第 ${round} 轮：<script>仅文本呈现</script>` }
      expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { codeDiagnosis: next } } } })).ok()).toBe(true)
      await panel.getByRole('button', { name: '🪄 局部 AI 调优代码诊断', exact: true }).click()
      const ai = page.getByRole('dialog', { name: 'AI 优化', exact: true })
      await expect(ai).toContainText('当前缺陷的代码诊断与补丁')
      await ai.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(`第 ${round} 轮只优化代码结论，保留其他信息`)
      await ai.getByRole('button', { name: '提交反馈', exact: true }).click()
      await expect(ai.getByText(/可以继续输入下一轮反馈/)).toBeVisible()
      await ai.locator('.arco-drawer-close-btn').click()
      await expect(panel.getByLabel('代码根因推测', { exact: true })).toHaveValue(next.root_cause)
      bug = await (await request.get(`/api/projects/${project.id}/assets/${bug.id}`)).json() as Asset
      expect(bug.data.codeDiagnosis).toEqual(next)
      expect(bug.source).toBe('AI')
      expect({ ...bug, data: { ...bug.data, codeDiagnosis: original.data.codeDiagnosis }, version: original.version, updatedAt: original.updatedAt, source: original.source }).toEqual(original)
      expect(await (await request.get(`/api/projects/${project.id}/assets/${other.id}`)).json()).toEqual(other)
    }
    expect(inputs).toHaveLength(2)
    for (const input of inputs) expect(input).toMatchObject({ targetId: bug.id, targetType: 'BUG', targetFields: ['codeDiagnosis'] })
    expect(inputs[1]?.conversationId).toBe(inputs[0]?.conversationId)
    await expect(panel.locator('script')).toHaveCount(0)
    await page.setViewportSize({ width: 1440, height: 1000 }); await panel.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('code-rca-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => drawer.evaluate(element => Math.round(element.getBoundingClientRect().right))).toBe(390)
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('code-rca-mobile.png') })
    expect(errors).toEqual([])
  } finally {
    const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    await removeSources(root)
  }
})

test('late evidence stays with its bug and a dirty human evaluation requires explicit conflict recovery', async ({ page, request }) => {
  const project = await (await request.post('/api/projects', { data: { name: `诊断读取隔离-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  let release: (() => void) | undefined
  const held = new Promise<void>(resolve => { release = resolve })
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  try {
    const old = await create(request, project.id, '旧缺陷证据', { rootCauseAnalysis: '旧缺陷的人工结论' })
    const next = await create(request, project.id, '新缺陷评价', { rootCauseAnalysis: '本缺陷的第一版结论' })
    expect((await request.post(`/api/projects/${project.id}/bugs/${old.id}/rca-evaluations`, { data: { baseVersion: old.version, verdict: 'CORRECT', regression: 'CONFIRMED', note: '只属于旧缺陷的人工评价', idempotencyKey: crypto.randomUUID() } })).ok()).toBe(true)
    let fetched = 0, settled = 0
    await page.route(`**/api/projects/${project.id}/bugs/${old.id}/**`, async route => {
      const response = await route.fetch(); fetched++
      await held
      try { await route.fulfill({ response }) } catch { /* The old drawer aborts these completed reads when it closes. */ }
      settled++
    })
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/bugs'); await page.getByRole('button', { name: `编辑 ${old.name}`, exact: true }).click()
    const previous = page.getByRole('dialog', { name: old.name, exact: true })
    await previous.getByRole('tab', { name: '源码诊断', exact: true }).click()
    await expect.poll(() => fetched).toBeGreaterThanOrEqual(2)
    await previous.locator('.arco-drawer-close-btn').click()
    await page.getByRole('button', { name: `编辑 ${next.name}`, exact: true }).click()
    const drawer = page.getByRole('dialog', { name: next.name, exact: true })
    await drawer.getByRole('tab', { name: '源码诊断', exact: true }).click()
    const panel = drawer.getByRole('region', { name: '源码诊断', exact: true })
    release?.(); await expect.poll(() => settled).toBe(fetched)
    await expect(panel.getByTestId('rca-current-evaluation')).toContainText('未评价')
    await expect(panel).not.toContainText('只属于旧缺陷的人工评价')
    await panel.getByLabel('RCA 人工评价', { exact: true }).selectOption('PARTIAL')
    await panel.getByLabel('评价备注', { exact: true }).fill('人工核对后的评价草稿')
    const remote = await request.patch(`/api/projects/${project.id}/assets/${next.id}`, { data: { baseVersion: next.version, data: { rootCauseAnalysis: '远端更新的第二版结论' } } })
    expect(remote.ok()).toBe(true)
    const updated = await remote.json() as Asset
    await drawer.getByRole('button', { name: '刷新当前记录', exact: true }).click()
    await expect(panel.getByLabel('评价备注', { exact: true })).toHaveValue('人工核对后的评价草稿')
    await panel.getByRole('button', { name: '保存人工评价', exact: true }).click()
    await expect(panel.getByRole('button', { name: '已核对最新诊断，保留评价继续', exact: true })).toBeVisible()
    expect((await (await request.get(`/api/projects/${project.id}/bugs/${next.id}/rca-evaluations`)).json()).total).toBe(0)
    await panel.getByRole('button', { name: '已核对最新诊断，保留评价继续', exact: true }).click()
    await panel.getByRole('button', { name: '保存人工评价', exact: true }).click()
    await expect(panel.getByRole('status').filter({ hasText: '人工评价已保存' })).toBeVisible()
    const state = await (await request.get(`/api/projects/${project.id}/bugs/${next.id}/rca-evaluations`)).json()
    expect(state.total).toBe(1)
    expect(state.current).toMatchObject({ assetVersion: updated.version, verdict: 'PARTIAL', note: '人工核对后的评价草稿' })
    expect(await (await request.get(`/api/projects/${project.id}/assets/${old.id}`)).json()).toEqual(old)
    expect(await (await request.get(`/api/projects/${project.id}/assets/${next.id}`)).json()).toEqual(updated)
    expect(errors).toEqual([])
  } finally {
    release?.()
    const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
  }
})
