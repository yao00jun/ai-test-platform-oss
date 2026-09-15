import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const requirementText = '# 账单需求\n订单金额必须展示两位小数。'
const apiDocument = JSON.stringify({ openapi: '3.0.3', info: { title: '账单接口', version: '1' }, paths: { '/__business/orders': { post: { operationId: 'orders', responses: { '201': { description: 'created' } } } } } })
const functional = 'featureCaseStart\n## 展示账单金额\n### 前置条件\n已有订单\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 查看账单 | 金额包含两位小数 |\n### 备注\nP1\nfeatureCaseEnd'
async function createProject(request: APIRequestContext) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
  const response = await request.post('/api/projects', { data: { name: `流水线恢复-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}
async function removeProject(request: APIRequestContext, projectId: string) {
  const pipelines = await (await request.get(`/api/ai/pipelines?projectId=${projectId}`)).json()
  for (const pipeline of pipelines) await request.post(`/api/ai/pipelines/${pipeline.id}/cancel`, { data: { projectId } })
  const current = await request.get(`/api/projects/${projectId}/assets/${projectId}`)
  if (current.ok()) await request.delete(`/api/projects/${projectId}/assets/${projectId}?baseVersion=${(await current.json()).version}`)
}
async function enqueueGeneration(request: APIRequestContext, projectId: string, requirementIds: string[], apiDefinitionIds: string[]) {
  const requirement = await (await request.get(`/api/projects/${projectId}/assets/${requirementIds[0]}`)).json() as Asset
  const responses: unknown[] = [
    { changes: [{ operation: 'MODIFY', targetType: 'REQUIREMENT', targetId: requirement.id, baseVersion: requirement.version, data: { analysis: { rules: ['金额展示两位小数'] } } }] },
    functional,
  ]
  if (apiDefinitionIds.length) responses.push({ changes: [{ operation: 'ADD', targetType: 'API_CASE', localKey: 'bill', name: '提交订单', data: { apiDefinitionId: apiDefinitionIds[0], method: 'POST', path: '/__business/orders', bodyType: 'JSON', body: { qty: 1 }, assertions: [{ type: 'status_code', expected: 201 }] } }] })
  for (const content of responses) expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content } })).ok()).toBe(true)
}
async function openWizard(page: Page, projectId: string): Promise<Locator> {
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), projectId)
  await page.goto('/')
  await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
  return page.getByRole('dialog', { name: '新建全自动测试', exact: true })
}
async function onlyGenerate(wizard: Locator) {
  await wizard.getByText('执行与证据配置', { exact: true }).click()
  await wizard.getByLabel('生成后执行测试计划', { exact: true }).uncheck()
}

test('lost document and import acknowledgements recover existing sources across reload without duplicate assets', async ({ page, request }) => {
  test.setTimeout(120000)
  const project = await createProject(request)
  const documentKeys: string[] = [], importIds: string[] = []
  let lostDocument = false, lostImport = false
  try {
    await page.route(`**/api/projects/${project.id}/documents/read`, async route => {
      documentKeys.push(route.request().postDataJSON().idempotencyKey)
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      if (!lostDocument) { lostDocument = true; await route.abort('connectionreset') } else await route.fulfill({ response })
    })
    await page.route(`**/api/projects/${project.id}/imports/*/apply`, async route => {
      importIds.push(route.request().url().split('/').at(-2)!)
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      if (!lostImport) { lostImport = true; await route.abort('connectionreset') } else await route.fulfill({ response })
    })
    await page.route('**/api/ai/pipelines', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const input = route.request().postDataJSON()
      await enqueueGeneration(request, project.id, input.requirementIds, input.apiDefinitionIds)
      await route.continue()
    })
    const wizard = await openWizard(page, project.id)
    await wizard.getByLabel('需求名称', { exact: true }).fill('订单账单')
    await wizard.getByLabel('BA 原始需求正文', { exact: true }).fill(requirementText)
    await wizard.getByLabel('开发接口文档内容', { exact: true }).fill(apiDocument)
    await onlyGenerate(wizard)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect(wizard.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=REQUIREMENT`)).json()).total).toBe(1)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect.poll(() => lostImport).toBe(true)
    await expect(wizard.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    expect(documentKeys).toHaveLength(2)
    expect(new Set(documentKeys).size).toBe(1)
    const persisted = await page.evaluate(id => JSON.parse(localStorage.getItem(`ai-test-platform:pipeline-preparation:${id}`)!), project.id)
    expect(persisted.requirementIds).toHaveLength(1)
    expect(persisted.importId).toBe(importIds[0])
    expect(JSON.stringify(persisted)).not.toContain(requirementText)
    expect(JSON.stringify(persisted)).not.toContain(apiDocument)
    await page.reload()
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await expect(wizard.getByLabel('需求来源', { exact: true })).toHaveValue('saved')
    await wizard.getByLabel('开发接口文档内容', { exact: true }).fill(apiDocument)
    await wizard.getByText('执行与证据配置', { exact: true }).click()
    await expect(wizard.getByLabel('生成后执行测试计划', { exact: true })).not.toBeChecked()
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    const detail = page.getByRole('dialog', { name: '全自动测试流水线', exact: true })
    await expect(detail.getByTestId('pipeline-status')).toHaveText('已完成 · 有待补充项', { timeout: 45000 })
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=REQUIREMENT`)).json()).total).toBe(1)
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=API_DEFINITION`)).json()).total).toBe(1)
    expect(documentKeys).toHaveLength(2)
    expect(importIds).toHaveLength(1)
    expect(await page.evaluate(id => localStorage.getItem(`ai-test-platform:pipeline-preparation:${id}`), project.id)).toBeNull()
  } finally { await removeProject(request, project.id) }
})

test('an uncertain pipeline submission resumes the exact request after reload', async ({ page, request }) => {
  const project = await createProject(request)
  const submissions: Record<string, unknown>[] = [], acceptedIds: string[] = []
  try {
    const requirement = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'REQUIREMENT', name: '现有需求', data: { content: requirementText } } })).json() as Asset
    await page.route('**/api/ai/pipelines', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const input = route.request().postDataJSON(); submissions.push(input)
      if (submissions.length === 1) await enqueueGeneration(request, project.id, input.requirementIds, input.apiDefinitionIds)
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      acceptedIds.push((await response.json()).pipelineId)
      if (submissions.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
    })
    const wizard = await openWizard(page, project.id)
    await wizard.getByLabel('需求来源', { exact: true }).selectOption('existing')
    await wizard.getByLabel('已保存需求', { exact: true }).selectOption(requirement.id)
    await wizard.getByLabel('接口来源', { exact: true }).selectOption('none')
    await onlyGenerate(wizard)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect(wizard.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
    await page.reload()
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await expect(wizard.getByLabel('需求来源', { exact: true })).toBeDisabled()
    await wizard.getByRole('button', { name: '恢复上次提交', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '全自动测试流水线', exact: true }).getByTestId('pipeline-status')).toHaveText('已完成 · 有待补充项', { timeout: 45000 })
    expect(submissions).toHaveLength(2)
    expect(submissions[1]).toEqual(submissions[0])
    expect(new Set(acceptedIds).size).toBe(1)
    expect(await (await request.get(`/api/ai/pipelines?projectId=${project.id}`)).json()).toHaveLength(1)
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()).total).toBe(1)
  } finally { await removeProject(request, project.id) }
})

test('supplying an environment resumes S6 and preserves edits while an earlier detail refresh arrives', async ({ page, request }) => {
  test.setTimeout(90000)
  const project = await createProject(request)
  let releaseRead: (() => void) | undefined
  try {
    const requirement = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'REQUIREMENT', name: '待运行需求', data: { content: requirementText } } })).json() as Asset
    await enqueueGeneration(request, project.id, [requirement.id], [])
    const accepted = await (await request.post('/api/ai/pipelines', { data: { projectId: project.id, requirementIds: [requirement.id], execute: true, idempotencyKey: crypto.randomUUID() } })).json()
    await expect.poll(async () => (await (await request.get(`/api/ai/pipelines/${accepted.pipelineId}?projectId=${project.id}`)).json()).status, { timeout: 45000 }).toBe('COMPLETED_WITH_GAPS')
    const originalCases = await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()
    const environment = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'ENVIRONMENT', name: '人工验收环境', data: { baseUrl: 'http://127.0.0.1:8082' } } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/')
    await page.getByRole('button', { name: `查看流水线 ${accepted.pipelineId.slice(0, 8)}`, exact: true }).click()
    const detail = page.getByRole('dialog', { name: '全自动测试流水线', exact: true })
    await expect(detail.locator('[data-stage="S6"]').getByTestId('stage-status')).toHaveText('待补充')
    let held = false
    await page.route(`**/api/ai/pipelines/${accepted.pipelineId}?**`, async route => {
      const response = await route.fetch()
      if (!held) { held = true; await new Promise<void>(resolve => { releaseRead = resolve }) }
      await route.fulfill({ response })
    })
    await detail.getByRole('button', { name: '刷新流水线', exact: true }).click()
    await expect.poll(() => !!releaseRead).toBe(true)
    await detail.getByRole('button', { name: '补充配置并恢复 S6', exact: true }).click()
    const resume = page.getByRole('dialog', { name: '补充配置并恢复阶段', exact: true })
    await resume.getByLabel('流水线执行环境', { exact: true }).selectOption(environment.id)
    await resume.getByLabel('生成后执行测试计划', { exact: true }).uncheck()
    const refreshed = page.waitForResponse(response => response.url().includes(`/api/ai/pipelines/${accepted.pipelineId}?`) && response.request().method() === 'GET')
    releaseRead!(); await refreshed
    await expect(resume.getByLabel('流水线执行环境', { exact: true })).toHaveValue(environment.id)
    await expect(resume.getByLabel('生成后执行测试计划', { exact: true })).not.toBeChecked()
    await resume.getByLabel('生成后执行测试计划', { exact: true }).check()
    await resume.getByRole('button', { name: '恢复所选阶段', exact: true }).click()
    await expect(detail.locator('[data-stage="S6"]').getByTestId('stage-status')).toHaveText('已完成', { timeout: 45000 })
    await detail.getByRole('button', { name: '查看流水线运行 1', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '执行记录', exact: true }).getByTestId('run-status')).toHaveText('待人工')
    expect(await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()).toEqual(originalCases)
    expect(await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()).toHaveLength(2)
  } finally { releaseRead?.(); await removeProject(request, project.id) }
})

test('a late document preparation stays with its original project after closing and switching workspaces', async ({ page, request }) => {
  const project = await createProject(request), other = await createProject(request)
  let releaseRead: (() => void) | undefined
  try {
    let readReturned = false
    await page.route(`**/api/projects/${project.id}/documents/read`, async route => {
      const response = await route.fetch()
      await new Promise<void>(resolve => { releaseRead = resolve })
      await route.fulfill({ response }); readReturned = true
    })
    const wizard = await openWizard(page, project.id)
    await wizard.getByLabel('BA 原始需求正文', { exact: true }).fill(requirementText)
    await wizard.getByLabel('接口来源', { exact: true }).selectOption('none')
    await onlyGenerate(wizard)
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    await expect.poll(() => !!releaseRead).toBe(true)
    await wizard.locator('.arco-drawer-close-btn').click()
    await page.getByLabel('当前项目', { exact: true }).click()
    await page.getByText(other.name, { exact: true }).click()
    releaseRead!()
    await expect.poll(() => readReturned).toBe(true)
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await expect(wizard.getByLabel('需求来源', { exact: true })).toHaveValue('paste')
    await expect(wizard.getByLabel('BA 原始需求正文', { exact: true })).toHaveValue('')
    expect((await (await request.get(`/api/projects/${other.id}/assets?type=REQUIREMENT`)).json()).total).toBe(0)
    expect(await (await request.get(`/api/ai/pipelines?projectId=${project.id}`)).json()).toHaveLength(0)
    await wizard.locator('.arco-drawer-close-btn').click()
    await page.getByLabel('当前项目', { exact: true }).click()
    await page.getByText(project.name, { exact: true }).click()
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    await expect(wizard.getByLabel('需求来源', { exact: true })).toHaveValue('saved')
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=REQUIREMENT`)).json()).total).toBe(1)
  } finally { releaseRead?.(); await removeProject(request, project.id); await removeProject(request, other.id) }
})
