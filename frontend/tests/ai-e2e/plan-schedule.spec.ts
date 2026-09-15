import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    const project = await (await request.post('/api/projects', { data: { name: `排期验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
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

test('a human enables a real patrol, previews trigger times and opens its actual run without losing an unsaved plan description', async ({ page, request, project }, testInfo) => {
  const manual = await create(request, project.id, 'FUNCTIONAL_CASE', '巡检后人工确认')
  const plan = await create(request, project.id, 'TEST_PLAN', '实际定时巡检', { diagnoseFailures: false })
  await create(request, project.id, 'PLAN_ITEM', '人工项', { targetId: manual.id, executionMode: 'MANUAL' }, plan.id)
  await page.goto('/plans')
  await page.getByRole('button', { name: '编辑 实际定时巡检', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '实际定时巡检', exact: true })
  await detail.getByRole('textbox', { name: '说明', exact: true }).fill('还没有保存的测试范围说明')
  await detail.getByRole('tab', { name: '巡检排期', exact: true }).click()
  const panel = detail.getByRole('region', { name: '巡检排期', exact: true })
  await panel.getByRole('textbox', { name: '巡检 Cron', exact: true }).fill('*/2 * * * * *')
  await panel.getByRole('textbox', { name: '巡检时区', exact: true }).fill('Asia/Shanghai')
  await panel.getByLabel('重叠执行策略', { exact: true }).selectOption('QUEUE')
  await panel.getByLabel('错过触发策略', { exact: true }).selectOption('FIRE_ONCE')
  await panel.getByRole('button', { name: '预览触发时间', exact: true }).click()
  await expect(panel.getByRole('list', { name: '未来触发时间', exact: true }).getByRole('listitem')).toHaveCount(5)
  expect((await (await request.get(`/api/projects/${project.id}/assets/${plan.id}`)).json()).version).toBe(plan.version)
  await panel.getByRole('checkbox', { name: '启用巡检', exact: true }).check()
  await panel.getByRole('button', { name: '保存排期', exact: true }).click()
  await expect(panel.getByText('排期已保存', { exact: true })).toBeVisible()
  await expect.poll(async () => {
    const state = await (await request.get(`/api/projects/${project.id}/plans/${plan.id}/schedule`)).json()
    return state.items.filter((item: { status: string }) => item.status === 'SUBMITTED').length
  }).toBeGreaterThan(0)
  await panel.getByRole('checkbox', { name: '启用巡检', exact: true }).uncheck()
  await panel.getByRole('button', { name: '保存排期', exact: true }).click()
  await expect(panel.getByText('排期已保存', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '刷新排期记录', exact: true }).click()
  await expect(panel).toContainText('已派发')
  const state = await (await request.get(`/api/projects/${project.id}/plans/${plan.id}/schedule`)).json()
  const occurrence = state.items.find((item: { runId: string }) => item.runId)
  await expect.poll(async () => (await (await request.get(`/api/jobs/${occurrence.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await page.screenshot({ path: testInfo.outputPath('schedule-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('schedule-mobile.png') })
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await panel.getByRole('button', { name: `查看巡检运行 ${occurrence.runId}`, exact: true }).click()
  const run = page.getByRole('dialog', { name: '执行记录', exact: true })
  await expect(run.getByTestId('run-status')).toHaveText('待人工')
  await run.locator('.arco-drawer-close-btn').click()
  await detail.getByRole('tab', { name: '详情与编辑', exact: true }).click()
  await expect(detail.getByRole('textbox', { name: '说明', exact: true })).toHaveValue('还没有保存的测试范围说明')
  expect((await (await request.get(`/api/projects/${project.id}/assets/${plan.id}`)).json()).data.scheduleEnabled).toBe(false)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('schedule conflict recovery preserves another editor description and only reapplies the chosen schedule fields', async ({ page, request, project }) => {
  const plan = await create(request, project.id, 'TEST_PLAN', '并发排期设置', { description: '初始说明', diagnoseFailures: false })
  await page.goto('/plans')
  await page.getByRole('button', { name: '编辑 并发排期设置', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '并发排期设置', exact: true })
  await detail.getByRole('tab', { name: '巡检排期', exact: true }).click()
  const panel = detail.getByRole('region', { name: '巡检排期', exact: true })
  await panel.getByRole('textbox', { name: '巡检 Cron', exact: true }).fill('0 0 9 * * *')
  await detail.getByRole('tab', { name: '详情与编辑', exact: true }).click()
  await detail.getByRole('tab', { name: '巡检排期', exact: true }).click()
  await expect(panel.getByRole('textbox', { name: '巡检 Cron', exact: true })).toHaveValue('0 0 9 * * *')
  expect((await request.patch(`/api/projects/${project.id}/assets/${plan.id}`, { data: { baseVersion: plan.version, data: { description: '同事的新说明', timezone: 'UTC' } } })).ok()).toBe(true)
  await panel.getByRole('button', { name: '保存排期', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('已被修改')
  await expect(panel.getByRole('textbox', { name: '巡检 Cron', exact: true })).toHaveValue('0 0 9 * * *')
  await panel.getByRole('button', { name: '保留我的修改并使用最新版本', exact: true }).click()
  await expect(panel.getByRole('textbox', { name: '巡检时区', exact: true })).toHaveValue('UTC')
  await panel.getByRole('button', { name: '保存排期', exact: true }).click()
  await expect(panel.getByText('排期已保存', { exact: true })).toBeVisible()
  const saved = await (await request.get(`/api/projects/${project.id}/assets/${plan.id}`)).json() as Asset
  expect(saved.data).toMatchObject({ description: '同事的新说明', timezone: 'UTC', cronExpression: '0 0 9 * * *', scheduleEnabled: false })
})
