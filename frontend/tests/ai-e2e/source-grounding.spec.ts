import { expect, test, type APIRequestContext } from '@playwright/test'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type { AcceptedJob, Asset } from '../../src/api/types'

async function queue(request: APIRequestContext, content: unknown, extra: Record<string, unknown> = {}) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content, ...extra } })).ok()).toBe(true)
}
async function removeSources(root: string) {
  if (path.dirname(root) !== path.resolve(tmpdir()) || !path.basename(root).startsWith('aitest-grounding-')) throw new Error('Unexpected fixture directory')
  await rm(root, { recursive: true, force: true })
}
const functional = 'featureCaseStart\n## 大额退款人工审核\n### 前置条件\n订单已支付\n### 测试步骤与预期结果\n|步骤|预期|\n|---|---|\n|提交 5001 元退款|人工审核提示|\n### 备注\nP1\nfeatureCaseEnd'
const apiDocument = JSON.stringify({ openapi: '3.0.3', info: { title: '退款接口', version: '1' }, paths: { '/refund': { post: { operationId: 'refund', responses: { '200': { description: '退款成功' } } } } } })

test('five-input preparation recovers exact analysis and pipeline identities and preserves source-bound local feedback', async ({ page, request }, testInfo) => {
  test.setTimeout(180000)
  await request.post('http://127.0.0.1:8082/__fixture/reset')
  const root = await mkdtemp(path.join(tmpdir(), 'aitest-grounding-'))
  await writeFile(path.join(root, 'Refund.java'), 'package shop;\n@RestController class Refund { @PostMapping("/refund") void refund(int amount) { if (amount > 5000) throw new IllegalArgumentException("manual review"); } }')
  await writeFile(path.join(root, 'Refund.vue'), '<template><button v-if="allowed" data-testid="refund-submit">退款</button></template>')
  const ddl = path.join(root, '业务结构.sql'); await writeFile(ddl, 'CREATE TABLE orders(id BIGINT PRIMARY KEY,state VARCHAR(20));')
  const project = await (await request.post('/api/projects', { data: { name: `源码流水线-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  const environment = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'ENVIRONMENT', name: '待绑定业务库的环境', data: { baseUrl: 'http://127.0.0.1:8082' } } })).json() as Asset
  const sourceInputs: Record<string, unknown>[] = [], pipelineInputs: Record<string, unknown>[] = [], sourceIds: string[] = [], pipelineIds: string[] = []
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
  try {
    await page.route(`**/api/projects/${project.id}/source-analyses`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      sourceInputs.push(route.request().postDataJSON())
      const response = await route.fetch(); expect(response.ok()).toBe(true); sourceIds.push((await response.json()).analysisId)
      if (sourceInputs.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    await page.route('**/api/ai/pipelines', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const input = route.request().postDataJSON(); pipelineInputs.push(input)
      if (pipelineInputs.length === 1) {
        const requirement = await (await request.get(`/api/projects/${project.id}/assets/${input.requirementIds[0]}`)).json() as Asset
        await queue(request, { changes: [{ operation: 'MODIFY', targetType: 'REQUIREMENT', targetId: requirement.id, baseVersion: requirement.version, data: { analysis: { blindSpots: ['源码中 amount > 5000 需要人工审核'] } } }] })
        await queue(request, functional)
        await queue(request, { changes: [{ operation: 'ADD', targetType: 'API_CASE', localKey: 'api', name: '退款 API', data: { apiDefinitionId: input.apiDefinitionIds[0], method: 'POST', path: '/refund' } }] })
        await queue(request, { changes: [{ operation: 'ADD', targetType: 'SQL_VALIDATION', localKey: 'sql', name: '订单状态 SQL', data: { sql: 'SELECT state FROM orders WHERE id=1', assertions: [{ type: 'field', field: 'state', expected: 'REFUNDED' }] } }] }, { parentFromTargetCase: true })
        await queue(request, { changes: [{ operation: 'ADD', targetType: 'UI_SCENARIO', localKey: 'page', name: '退款 UI', data: {} }, { operation: 'ADD', targetType: 'UI_STEP', localKey: 'submit', parentId: '@page', name: '退款按钮', data: { action: 'click', selector: 'testId=refund-submit' } }] })
      }
      const response = await route.fetch(); expect(response.ok()).toBe(true); pipelineIds.push((await response.json()).pipelineId)
      if (pipelineInputs.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    await page.goto('/')
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    const wizard = page.getByRole('dialog', { name: '新建全自动测试', exact: true })
    await wizard.getByLabel('BA 原始需求正文', { exact: true }).fill('退款成功后状态变为 REFUNDED。')
    await wizard.getByLabel('开发接口文档内容', { exact: true }).fill(apiDocument)
    await wizard.getByLabel('源码依据', { exact: true }).selectOption('prepare')
    await wizard.getByLabel('后端源码路径', { exact: true }).fill(root)
    await wizard.getByLabel('前端源码路径', { exact: true }).fill(root)
    await wizard.getByLabel('SQL 脚本路径', { exact: true }).fill(ddl)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect(wizard.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    await page.reload(); await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await expect(wizard.getByLabel('后端源码路径', { exact: true })).toHaveValue(root)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect(wizard.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    expect(sourceInputs).toHaveLength(2); expect(sourceInputs[1]).toEqual(sourceInputs[0]); expect(new Set(sourceIds).size).toBe(1)
    await page.reload(); await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await wizard.getByRole('button', { name: '恢复上次提交', exact: true }).click()
    const detail = page.getByRole('dialog', { name: '全自动测试流水线', exact: true })
    await expect(detail.getByTestId('pipeline-status')).toHaveText('已完成 · 有待补充项', { timeout: 60000 })
    expect(pipelineInputs).toHaveLength(2); expect(pipelineInputs[1]).toEqual(pipelineInputs[0]); expect(new Set(pipelineIds).size).toBe(1)
    expect(pipelineInputs[0]).toMatchObject({ sourceSnapshotId: sourceIds[0], environmentId: environment.id })
    await expect(detail.getByTestId('pipeline-source-snapshot')).toContainText(sourceIds[0]!)
    await detail.getByRole('button', { name: '全链路反馈优化', exact: true }).click()
    const pipelineFeedback = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await expect(pipelineFeedback.getByLabel('生成源码快照', { exact: true })).toHaveValue(sourceIds[0]!)
    await expect(pipelineFeedback.getByLabel('生成源码快照', { exact: true })).toBeDisabled()
    await pipelineFeedback.locator('.arco-drawer-close-btn').click()
    await expect(detail.locator('[data-stage="S6"]')).toContainText('SQL 草稿尚未绑定业务数据源')
    expect((await (await request.get(`/api/projects/${project.id}/source-analyses`)).json()).total).toBe(1)
    const saved = (await (await request.get(`/api/projects/${project.id}/assets?limit=200`)).json()).items as Asset[]
    const sql = saved.find(asset => asset.type === 'SQL_VALIDATION')!, uiStep = saved.find(asset => asset.type === 'UI_STEP')!
    expect(sql.data).toMatchObject({ sourceSnapshotId: sourceIds[0], databaseSourceId: '' })
    const row = detail.locator(`[data-asset-id="${sql.id}"]`)
    await row.getByRole('button', { name: `编辑 ${sql.name}`, exact: true }).click()
    const editor = page.getByRole('dialog', { name: sql.name, exact: true })
    await expect(editor.getByRole('region', { name: '生成依据', exact: true })).toContainText('orders')
    await expect(editor.getByRole('textbox', { name: '生成依据', exact: true })).toHaveCount(0)
    await editor.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
    const local = page.getByRole('dialog', { name: 'AI 优化', exact: true })
    for (const round of [2, 3]) {
      await queue(request, { data: { sql: `SELECT state FROM orders WHERE id=${round}` } })
      await local.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(`只调整订单为 ${round}，保留其他内容`)
      await local.getByRole('button', { name: '提交反馈', exact: true }).click()
      await expect(local.getByText(`已更新「${sql.name}」至版本 ${round}，可以继续输入下一轮反馈。`, { exact: true })).toBeVisible()
      const current = await (await request.get(`/api/projects/${project.id}/assets/${sql.id}`)).json() as Asset
      expect(current.id).toBe(sql.id); expect(current.position).toBe(sql.position); expect(current.data.sourceSnapshotId).toBe(sourceIds[0])
    }
    for (const sibling of saved.filter(asset => asset.id !== sql.id)) expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
    await local.locator('.arco-drawer-close-btn').click(); await editor.locator('.arco-drawer-close-btn').click()
    await detail.locator(`[data-asset-id="${uiStep.id}"]`).getByRole('button', { name: `编辑 ${uiStep.name}`, exact: true }).click()
    const uiEditor = page.getByRole('dialog', { name: uiStep.name, exact: true })
    await expect(uiEditor.getByRole('region', { name: '生成依据', exact: true })).toContainText('Refund.vue')
    await expect(uiEditor.getByRole('region', { name: '生成依据', exact: true })).toContainText('待运行验证')
    await page.screenshot({ path: testInfo.outputPath('source-grounding-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => uiEditor.evaluate(element => element.getBoundingClientRect().width)).toBeLessThan(395)
    await expect.poll(() => uiEditor.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('source-grounding-mobile.png') })
    await expect(page.locator('vite-error-overlay')).toHaveCount(0); expect(errors).toEqual([])
    expect(JSON.stringify(await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json())).toContain('amount > 5000')
  } finally {
    for (const id of new Set(pipelineIds)) await request.post(`/api/ai/pipelines/${id}/cancel`, { data: { projectId: project.id } })
    const current = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (current.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await current.json()).version}`)
    await removeSources(root)
  }
})

test('generic and global source selection survives uncertain replies and only selected additions are adopted', async ({ page, request }) => {
  test.setTimeout(120000)
  await request.post('http://127.0.0.1:8082/__fixture/reset')
  const project = await (await request.post('/api/projects', { data: { name: `生成来源-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  const sources: string[] = []
  try {
    for (const table of ['orders', 'archive']) {
      const response = await request.post(`/api/projects/${project.id}/source-analyses`, { data: { ddlText: `CREATE TABLE ${table}(id BIGINT PRIMARY KEY,state VARCHAR(20));`, idempotencyKey: crypto.randomUUID() } })
      expect(response.ok()).toBe(true)
      const submitted = await response.json(); sources.push(submitted.analysisId)
      await expect.poll(async () => (await (await request.get(`/api/jobs/${submitted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
    }
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/api-tests')
    await page.getByRole('tab', { name: 'SQL 校验', exact: true }).click()
    await page.getByRole('button', { name: 'AI 生成', exact: true }).click()
    const generic = page.getByRole('dialog', { name: 'AI 生成', exact: true })
    await generic.getByLabel('生成源码快照', { exact: true }).selectOption(sources[0]!)
    await queue(request, { changes: [{ operation: 'ADD', targetType: 'SQL_VALIDATION', localKey: 'sql', name: '订单查询', data: { sql: 'SELECT state FROM orders' } }] })
    const generationInputs: Record<string, unknown>[] = [], generationReplies: AcceptedJob[] = []
    await page.route('**/api/ai/generate', async route => {
      generationInputs.push(route.request().postDataJSON())
      const response = await route.fetch(); generationReplies.push(await response.json() as AcceptedJob)
      if (generationReplies.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    await generic.getByRole('textbox', { name: '生成要求', exact: true }).fill('使用订单版本生成查询')
    await generic.getByRole('button', { name: '开始生成', exact: true }).click()
    await expect(generic.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    await generic.getByRole('button', { name: '开始生成', exact: true }).click()
    await expect(generic.getByText('生成已完成，测试资产列表已刷新。', { exact: true })).toBeVisible()
    expect(generationInputs[1]).toEqual(generationInputs[0]); expect(generationInputs[0]?.sourceSnapshotId).toBe(sources[0])
    expect(generationReplies[1]?.jobId).toBe(generationReplies[0]?.jobId)
    const original = (await (await request.get(`/api/projects/${project.id}/assets?type=SQL_VALIDATION`)).json()).items[0] as Asset
    expect(original.data.sourceSnapshotId).toBe(sources[0])
    await generic.locator('.arco-drawer-close-btn').click()
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    const global = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await global.getByLabel('生成源码快照', { exact: true }).selectOption(sources[1]!)
    await queue(request, { changes: [{ operation: 'ADD', targetType: 'SQL_VALIDATION', localKey: 'archive', name: '归档查询', data: { sql: 'SELECT state FROM archive' } }, { operation: 'ADD', targetType: 'SQL_VALIDATION', localKey: 'ignored', name: '不采纳查询', data: { sql: 'SELECT id FROM archive' } }] })
    const feedbackInputs: Record<string, unknown>[] = [], feedbackReplies: AcceptedJob[] = []
    await page.route('**/api/ai/feedback', async route => {
      feedbackInputs.push(route.request().postDataJSON())
      const response = await route.fetch(); feedbackReplies.push(await response.json() as AcceptedJob)
      if (feedbackReplies.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    await global.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill('新增归档版本查询，保留订单资产')
    await global.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(global.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    await global.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(global.getByRole('heading', { name: '候选变更', exact: true })).toBeVisible()
    expect(feedbackInputs[1]).toEqual(feedbackInputs[0]); expect(feedbackInputs[0]?.sourceSnapshotId).toBe(sources[1])
    expect(feedbackReplies[1]?.jobId).toBe(feedbackReplies[0]?.jobId)
    await global.getByLabel('选择新增 归档查询', { exact: true }).check()
    await global.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
    await expect(global.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
    const saved = (await (await request.get(`/api/projects/${project.id}/assets?type=SQL_VALIDATION`)).json()).items as Asset[]
    expect(saved).toHaveLength(2); expect(saved.find(asset => asset.id === original.id)).toEqual(original)
    expect(saved.find(asset => asset.id !== original.id)?.data.sourceSnapshotId).toBe(sources[1])
    await page.reload(); await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    await expect(global.getByLabel('生成源码快照', { exact: true })).toHaveValue(sources[1]!)
    expect(await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()).toHaveLength(2)
  } finally {
    const current = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (current.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await current.json()).version}`)
  }
})
