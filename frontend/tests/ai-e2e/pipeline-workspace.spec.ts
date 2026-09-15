import { expect, test, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const apiDocument = JSON.stringify({ openapi: '3.0.3', info: { title: '订单服务', version: '1.0' }, paths: { '/__business/orders': { post: { operationId: 'createOrder', summary: '创建订单', responses: { '201': { description: '已创建' } } } } } })
const functional = 'featureCaseStart\n## 新订单可查看账单\n### 前置条件\n订单已经创建\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 打开账单 | 金额正确 |\n### 备注\nP1\nfeatureCaseEnd'
async function reply(request: APIRequestContext, content: unknown, delayMs = 0) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content, delayMs } })).ok()).toBe(true)
}

test('one click ingests real sources and tracks six durable stages without treating the first job as completion', async ({ page, request }) => {
  test.setTimeout(120000)
  await request.post('http://127.0.0.1:8082/__fixture/reset')
  const project = await (await request.post('/api/projects', { data: { name: `流水线验收-${crypto.randomUUID().slice(0, 6)}`, data: {} } })).json() as Asset
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
  let submitted: { pipelineId: string; conversationId: string } | undefined
  let droppedRead = false
  try {
    await page.route('**/api/ai/pipelines/*?*', async route => {
      const response = await route.fetch()
      const value = await response.json()
      if (!droppedRead && value.status === 'RUNNING' && value.steps.some((step: { stage: string; status: string }) => step.stage === 'S1' && step.status === 'COMPLETED')) { droppedRead = true; await route.abort('connectionreset') }
      else await route.fulfill({ response })
    })
    await page.route('**/api/ai/pipelines', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const input = route.request().postDataJSON()
      const requirement = await (await request.get(`/api/projects/${project.id}/assets/${input.requirementIds[0]}`)).json() as Asset
      const definition = await (await request.get(`/api/projects/${project.id}/assets/${input.apiDefinitionIds[0]}`)).json() as Asset
      await reply(request, { changes: [{ operation: 'MODIFY', targetType: 'REQUIREMENT', targetId: requirement.id, baseVersion: requirement.version, data: { analysis: { requirements: ['订单账单金额正确'], blindSpots: ['重复创建的幂等行为需要确认'] } } }] })
      await reply(request, functional, 7000)
      await reply(request, { changes: [
        { operation: 'ADD', targetType: 'API_CASE', localKey: 'api', name: '创建订单返回成功', data: { apiDefinitionId: definition.id, method: 'POST', path: '/__business/orders', bodyType: 'JSON', body: { qty: 1 }, assertions: [{ type: 'status_code', expected: 201 }] } },
        { operation: 'ADD', targetType: 'SCENARIO', localKey: 'chain', name: '订单业务链', data: {} },
        { operation: 'ADD', targetType: 'SCENARIO_STEP', localKey: 'step', parentId: '@chain', name: '提交订单', data: { targetId: '@api', stepType: 'HTTP' } },
      ] })
      const response = await route.fetch(); submitted = await response.json(); await route.fulfill({ response })
    })
    await page.goto('/')
    await page.getByRole('button', { name: '一键全自动生成全套测试资产', exact: true }).click()
    const wizard = page.getByRole('dialog', { name: '新建全自动测试', exact: true })
    await wizard.getByLabel('需求名称', { exact: true }).fill('订单需求')
    await wizard.getByLabel('BA 原始需求正文', { exact: true }).fill('# 订单需求\n用户创建订单后可以查看账单金额。')
    await wizard.getByLabel('开发接口文档内容', { exact: true }).fill(apiDocument)
    await wizard.getByText('执行与证据配置', { exact: true }).click()
    await wizard.getByLabel('生成后执行测试计划', { exact: true }).uncheck()
    await wizard.getByRole('button', { name: '开始生成测试资产', exact: true }).click()
    const detail = page.getByRole('dialog', { name: '全自动测试流水线', exact: true })
    await expect(detail.locator('[data-stage="S1"]').getByTestId('stage-status')).toHaveText('已完成')
    await expect(detail.getByTestId('pipeline-status')).toHaveText('运行中')
    await expect(detail.getByTestId('pipeline-status')).toHaveText('已完成 · 有待补充项', { timeout: 45000 })
    expect(droppedRead).toBe(true)
    expect(submitted?.pipelineId).toBeTruthy()
    await expect(detail.locator('[data-stage="S4"]')).toContainText('未提供 DDL 或真实业务数据库连接')
    await expect(detail.locator('[data-stage="S4"]').getByTestId('stage-status')).toHaveText('待补充')
    await expect(detail.locator('[data-stage="S5"]')).toContainText('缺少页面、人工录制或静态组件证据')
    await expect(detail.locator('[data-stage="S5"]').getByTestId('stage-status')).toHaveText('待补充')
    const saved = await (await request.get(`/api/ai/pipelines/${submitted!.pipelineId}?projectId=${project.id}`)).json()
    expect(saved.steps).toHaveLength(6)
    expect(saved.execution.execution).toBe('NOT_REQUESTED')
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=REQUIREMENT`)).json()).total).toBe(1)
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=API_DEFINITION`)).json()).total).toBe(1)
    await page.reload()
    await page.getByRole('button', { name: `查看流水线 ${submitted!.pipelineId.slice(0, 8)}`, exact: true }).click()
    await expect(detail.getByTestId('pipeline-status')).toHaveText('已完成 · 有待补充项')
    await detail.getByRole('button', { name: '全链路反馈优化', exact: true }).click()
    const feedback = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await expect(feedback.getByRole('log', { name: 'AI 对话记录' })).toContainText('S1 自动生成')
    await expect(feedback.getByRole('button', { name: '新建会话', exact: true })).toHaveCount(0)
    const cases = (await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()).items as Asset[]
    const original = cases[0]!
    const children = await (await request.get(`/api/projects/${project.id}/assets?parentId=${original.id}`)).json()
    const sibling = (await (await request.get(`/api/projects/${project.id}/assets?type=API_CASE`)).json()).items[0] as Asset
    let target = await (await request.patch(`/api/projects/${project.id}/assets/${original.id}`, { data: { baseVersion: original.version, data: { precondition: '人工补充：账单应包含运费' } } })).json() as Asset
    for (const [instruction, remark] of [['补充金额精度说明，保留我的前置条件', '金额保留两位小数'], ['仍不满意，继续补充币种显示', '金额保留两位小数，并显示人民币币种']]) {
      await reply(request, { changes: [{ operation: 'MODIFY', targetType: 'FUNCTIONAL_CASE', targetId: target.id, baseVersion: target.version, data: { remark } }] })
      await feedback.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill(instruction!)
      await feedback.getByRole('button', { name: '生成改进候选', exact: true }).click()
      await expect(feedback.getByText(remark!, { exact: true })).toBeVisible()
      await feedback.locator('[data-change-operation="MODIFY"]').getByLabel(`选择修改 ${target.name}`, { exact: true }).check()
      await feedback.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
      await expect(feedback.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
      target = await (await request.get(`/api/projects/${project.id}/assets/${original.id}`)).json() as Asset
      expect(target.data).toMatchObject({ precondition: '人工补充：账单应包含运费', remark })
      expect(target.id).toBe(original.id)
      expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
      expect(await (await request.get(`/api/projects/${project.id}/assets?parentId=${original.id}`)).json()).toEqual(children)
    }
    const modelRequests = await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()
    expect(JSON.stringify(modelRequests.at(-1))).toContain('人工补充：账单应包含运费')
    expect(JSON.stringify(modelRequests.at(-1))).toContain('补充金额精度说明')
    const conversation = await (await request.get(`/api/ai/conversations/${submitted!.conversationId}?projectId=${project.id}`)).json()
    expect(conversation).toMatchObject({ id: submitted!.conversationId, scope: 'PIPELINE', targetId: submitted!.pipelineId })
    const feedbackMessages = conversation.messages.filter((message: { role: string; content: string }) => message.role === 'user' && !message.content.startsWith('S'))
    expect(feedbackMessages.map((message: { content: string }) => message.content)).toEqual(['补充金额精度说明，保留我的前置条件', '仍不满意，继续补充币种显示'])
    await feedback.locator('.arco-drawer-close-btn').click()
    const targetRow = detail.locator(`[data-asset-id="${target.id}"]`)
    await expect.soft(targetRow.locator('td.mono')).toHaveText(`v${target.version}`)
    await targetRow.getByRole('button', { name: `编辑 ${target.name}`, exact: true }).click()
    const assetDetail = page.getByRole('dialog', { name: target.name, exact: true })
    await assetDetail.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
    const local = page.getByRole('dialog', { name: 'AI 优化', exact: true })
    await reply(request, { data: { remark: '局部补充：按银行家舍入规则展示金额' } })
    await local.getByRole('textbox', { name: '本轮反馈', exact: true }).fill('只调整本条用例的舍入规则说明')
    await local.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(local.getByText(`已更新「${target.name}」至版本 ${Number(target.version) + 1}，可以继续输入下一轮反馈。`, { exact: true })).toBeVisible()
    await local.locator('.arco-drawer-close-btn').click()
    await expect(assetDetail.getByRole('textbox', { name: '备注', exact: true })).toHaveValue('局部补充：按银行家舍入规则展示金额')
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
    expect(await (await request.get(`/api/projects/${project.id}/assets?parentId=${original.id}`)).json()).toEqual(children)
  } finally {
    if (submitted?.pipelineId) await request.post(`/api/ai/pipelines/${submitted.pipelineId}/cancel`, { data: { projectId: project.id } })
    const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
  }
})
