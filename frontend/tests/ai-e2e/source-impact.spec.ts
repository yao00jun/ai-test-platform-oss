import { expect, test as base, type APIRequestContext } from '@playwright/test'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ source: { project: Asset; root: string; baseline: string; head: string; refund: Asset; stable: Asset } }>({
  source: async ({ page, request }, use) => {
    const root = await mkdtemp(path.join(tmpdir(), 'aitest-impact-'))
    const project = await (await request.post('/api/projects', { data: { name: `影响分析-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    const create = async (name: string, route: string) => (await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'API_CASE', name, data: { path: route } } })).json()) as Asset
    const refund = await create('退款回归', '/refund'), stable = await create('未修改接口', '/stable')
    const before = 'package shop;\n@RestController class Api {\n @GetMapping("/refund") int refund() {\n  return 1;\n }\n @GetMapping("/stable") int stable() { return 9; }\n}'
    await writeFile(path.join(root, 'Api.java'), before)
    const baseline = await capture(request, project.id, root)
    await writeFile(path.join(root, 'Api.java'), before.replace('return 1;', 'return 2;'))
    const head = await capture(request, project.id, root)
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use({ project, root, baseline, head, refund, stable }) } finally {
      await cleanup(request, project.id, root)
      expect(errors).toEqual([])
    }
  },
})
async function capture(request: APIRequestContext, project: string, root: string) {
  const response = await request.post(`/api/projects/${project}/source-analyses`, { data: { backendRepoPath: root, idempotencyKey: crypto.randomUUID() } })
  expect(response.ok()).toBe(true); const submitted = await response.json()
  await expect.poll(async () => (await (await request.get(`/api/projects/${project}/source-analyses/${submitted.analysisId}`)).json()).status, { timeout: 45000 }).toBe('READY')
  return submitted.analysisId as string
}
async function cleanup(request: APIRequestContext, project: string, root: string) {
  const current = await request.get(`/api/projects/${project}/assets/${project}`)
  if (current.ok()) await request.delete(`/api/projects/${project}/assets/${project}?baseVersion=${(await current.json()).version}`)
  if (path.dirname(root) !== path.resolve(tmpdir()) || !path.basename(root).startsWith('aitest-impact-')) throw new Error('Unexpected fixture path')
  await rm(root, { recursive: true, force: true })
}

test('source impact displays precise methods and creates only the human-selected ordinary plan', async ({ page, request, source }, testInfo) => {
  await page.goto('/projects')
  await page.getByRole('button', { name: `查看源码快照 ${source.head.slice(0, 8)}`, exact: true }).click()
  const panel = page.getByRole('region', { name: '源码变更与定向回归', exact: true })
  await panel.getByLabel('对比基线快照', { exact: true }).selectOption(source.baseline)
  await panel.getByRole('button', { name: '分析变更影响', exact: true }).click()
  await expect(panel.getByTestId('impact-status')).toHaveText('影响分析已完成', { timeout: 45000 })
  await expect(panel.getByTestId('changed-methods')).toContainText('refund()')
  await expect(panel.getByTestId('changed-methods')).not.toContainText('stable()')
  await expect(panel.getByRole('checkbox', { name: '选择回归测试 未修改接口', exact: true })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '创建选定回归计划', exact: true })).toBeDisabled()
  await panel.getByRole('checkbox', { name: '选择回归测试 退款回归', exact: true }).check()
  await panel.getByLabel('回归计划名称', { exact: true }).fill('我的定向回归')
  await panel.getByRole('button', { name: '创建选定回归计划', exact: true }).click()
  await expect(panel.getByText('回归计划已创建', { exact: true })).toBeVisible()
  const plans = (await (await request.get(`/api/projects/${source.project.id}/assets?type=TEST_PLAN&limit=100`)).json()).items as Asset[]
  expect(plans).toHaveLength(1); expect(plans[0]!.data.sourceSnapshotId).toBe(source.head)
  const items = (await (await request.get(`/api/projects/${source.project.id}/assets?type=PLAN_ITEM&parentId=${plans[0]!.id}&limit=100`)).json()).items as Asset[]
  expect(items.map(item => item.data.targetId)).toEqual([source.refund.id])
  await panel.getByRole('button', { name: '打开回归计划', exact: true }).click()
  await expect(page.getByRole('dialog').filter({ hasText: '我的定向回归' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.locator('.arco-drawer-mask')).toHaveCount(0)
  await panel.getByRole('heading', { name: '源码变更与定向回归', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('source-impact-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => panel.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(280)
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('source-impact-mobile.png') })
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('lost analysis and plan acknowledgements recover exact submissions after reload and human edits reject stale selection', async ({ page, request, source }) => {
  const analyses: unknown[] = [], plans: unknown[] = []
  await page.route(`**/api/projects/${source.project.id}/source-analyses/${source.head}/impact`, async route => {
    analyses.push(route.request().postDataJSON()); const response = await route.fetch(); expect(response.ok()).toBe(true)
    if (analyses.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
  })
  await page.goto('/projects')
  const panel = page.getByRole('region', { name: '源码变更与定向回归', exact: true })
  await panel.getByLabel('对比基线快照', { exact: true }).selectOption(source.baseline)
  await panel.getByRole('button', { name: '分析变更影响', exact: true }).click()
  await expect(panel.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
  await page.reload()
  await panel.getByRole('button', { name: '恢复影响分析提交', exact: true }).click()
  await expect(panel.getByTestId('impact-status')).toHaveText('影响分析已完成', { timeout: 45000 })
  expect(analyses).toHaveLength(2); expect(analyses[1]).toEqual(analyses[0])
  await panel.getByRole('checkbox', { name: '选择回归测试 退款回归', exact: true }).check()
  expect((await request.patch(`/api/projects/${source.project.id}/assets/${source.refund.id}`, { data: { baseVersion: source.refund.version, name: '人工修改后退款' } })).ok()).toBe(true)
  await panel.getByRole('button', { name: '创建选定回归计划', exact: true }).click()
  await expect(panel.getByRole('alert').filter({ hasText: '已被修改' })).toBeVisible()
  expect((await (await request.get(`/api/projects/${source.project.id}/assets?type=TEST_PLAN&limit=100`)).json()).total).toBe(0)
  await panel.getByRole('button', { name: '分析变更影响', exact: true }).click()
  await expect(panel.getByRole('checkbox', { name: '选择回归测试 人工修改后退款', exact: true })).toBeVisible({ timeout: 45000 })
  await panel.getByRole('checkbox', { name: '选择回归测试 人工修改后退款', exact: true }).check()
  await page.route(`**/api/projects/${source.project.id}/source-impacts/*/regression-plan`, async route => {
    plans.push(route.request().postDataJSON()); const response = await route.fetch(); expect(response.ok()).toBe(true)
    if (plans.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
  })
  await panel.getByRole('button', { name: '创建选定回归计划', exact: true }).click()
  await expect(panel.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
  await page.reload()
  await panel.getByRole('button', { name: '恢复回归计划提交', exact: true }).click()
  await expect(panel.getByText('回归计划已创建', { exact: true })).toBeVisible()
  expect(plans).toHaveLength(2); expect(plans[1]).toEqual(plans[0])
  expect((await (await request.get(`/api/projects/${source.project.id}/assets?type=TEST_PLAN&limit=100`)).json()).total).toBe(1)
})

test('late impact and plan replies stay attached to their original source snapshot', async ({ page, request, source }) => {
  let release: (() => void) | undefined, accepted = false
  const panel = page.getByRole('region', { name: '源码变更与定向回归', exact: true })
  try {
    await page.route(`**/api/projects/${source.project.id}/source-analyses/${source.head}/impact`, async route => {
      const response = await route.fetch(); expect(response.ok()).toBe(true)
      await new Promise<void>(resolve => { release = resolve })
      await route.fulfill({ response }); accepted = true
    })
    await page.goto('/projects')
    await panel.getByLabel('对比基线快照', { exact: true }).selectOption(source.baseline)
    await panel.getByRole('button', { name: '分析变更影响', exact: true }).click()
    await expect.poll(() => !!release).toBe(true)
    await page.getByRole('button', { name: `查看源码快照 ${source.baseline.slice(0, 8)}`, exact: true }).click()
    release!(); await expect.poll(() => accepted).toBe(true)
    await expect(panel.getByTestId('impact-status')).toHaveCount(0)
    await page.getByRole('button', { name: `查看源码快照 ${source.head.slice(0, 8)}`, exact: true }).click()
    await expect(panel.getByTestId('impact-status')).toHaveText('影响分析已完成', { timeout: 45000 })
    await panel.getByRole('checkbox', { name: '选择回归测试 退款回归', exact: true }).check()
    release = undefined; accepted = false
    await page.route(`**/api/projects/${source.project.id}/source-impacts/*/regression-plan`, async route => {
      const response = await route.fetch(); expect(response.ok()).toBe(true)
      await new Promise<void>(resolve => { release = resolve })
      await route.fulfill({ response }); accepted = true
    })
    await panel.getByRole('button', { name: '创建选定回归计划', exact: true }).click()
    await expect.poll(() => !!release).toBe(true)
    await page.getByRole('button', { name: `查看源码快照 ${source.baseline.slice(0, 8)}`, exact: true }).click()
    release!(); await expect.poll(() => accepted).toBe(true)
    await expect(panel.getByRole('button', { name: '打开回归计划', exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: `查看源码快照 ${source.head.slice(0, 8)}`, exact: true }).click()
    await expect(panel.getByText('回归计划已创建', { exact: true })).toBeVisible()
    expect((await (await request.get(`/api/projects/${source.project.id}/source-analyses/${source.head}/impacts`)).json()).total).toBe(1)
    expect((await (await request.get(`/api/projects/${source.project.id}/source-analyses/${source.baseline}/impacts`)).json()).total).toBe(0)
    expect((await (await request.get(`/api/projects/${source.project.id}/assets?type=TEST_PLAN&limit=100`)).json()).total).toBe(1)
  } finally { release?.() }
})
