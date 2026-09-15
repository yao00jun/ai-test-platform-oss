import { expect, test as base, type APIRequestContext } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import path from 'node:path'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const response = await request.post('/api/projects', { data: { name: `晨报-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
    expect(response.ok()).toBe(true)
    const project = await response.json() as Asset
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
const settingsPath = (project: string) => `/api/projects/${project}/morning-brief/schedule`
const historyPath = (project: string) => `/api/projects/${project}/morning-brief/history`
const settings = (baseVersion: string, enabled: boolean) => ({ baseVersion, enabled, time: '00:00', timezone: 'UTC', instruction: '分析昨天的真实测试记录', maxRetries: 0 })
async function due(project: string) {
  expect(project).toMatch(/^[a-f0-9]{32}$/)
  const runtime = path.resolve('../.runtime')
  const connection = JSON.parse(await readFile(path.join(runtime, 'mysql/connection.json'), 'utf8')) as { home: string }
  await promisify(execFile)(path.join(connection.home, 'bin/mysql.exe'), [
    `--defaults-file=${path.join(runtime, 'mysql/admin.cnf')}`, '--database=ai_test_platform_e2e', '--batch',
    '--execute', `UPDATE morning_brief_schedule SET next_fire_at=UTC_DATE() WHERE project_id='${project}' AND enabled=TRUE`,
  ], { windowsHide: true, timeout: 10000 })
}
async function reply(request: APIRequestContext, content: string) {
  expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [{ operation: 'ADD', targetType: 'QUALITY_BRIEF', name: '昨天的质量晨报', data: { content } }] } } })).ok()).toBe(true)
}

test('morning settings stay disabled by default and preserve a dirty draft through a CAS conflict', async ({ page, request, project }, testInfo) => {
  await page.goto('/')
  const panel = page.getByRole('region', { name: '自动晨报', exact: true })
  await expect(panel).toContainText('尚未启用')
  await panel.getByRole('button', { name: '晨报设置', exact: true }).click()
  const form = panel.getByRole('region', { name: '晨报设置', exact: true })
  await expect(form.getByRole('checkbox', { name: '启用每日晨报', exact: true })).not.toBeChecked()
  await form.getByRole('textbox', { name: '生成要求', exact: true }).fill('保留人工发布门槛，分析失败和待人工项')
  await form.getByLabel('晨报时区', { exact: true }).fill('America/New_York')
  await form.getByLabel('当地时间', { exact: true }).fill('09:45')
  await form.getByRole('button', { name: '保存晨报设置', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('v1')
  await form.getByRole('textbox', { name: '生成要求', exact: true }).fill('尚未保存的人工要求')
  expect((await request.put(settingsPath(project.id), { data: { ...settings('1', false), time: '10:00', timezone: 'America/New_York', instruction: '另一位编辑者的要求' } })).ok()).toBe(true)
  await form.getByRole('button', { name: '保存晨报设置', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('设置已变化')
  await expect(form.getByRole('textbox', { name: '生成要求', exact: true })).toHaveValue('尚未保存的人工要求')
  await form.getByRole('button', { name: '读取最新设置并保留我的修改', exact: true }).click()
  await expect(form.getByLabel('当地时间', { exact: true })).toHaveValue('10:00')
  await form.getByRole('button', { name: '保存晨报设置', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('v3')
  await page.reload()
  await panel.getByRole('button', { name: '晨报设置', exact: true }).click()
  await expect(form.getByRole('textbox', { name: '生成要求', exact: true })).toHaveValue('尚未保存的人工要求')
  expect((await (await request.get(settingsPath(project.id))).json()).enabled).toBe(false)
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('morning-brief-settings-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(async () => page.locator('.app-body').evaluate(element => element.getBoundingClientRect().x)).toBe(0)
  await panel.scrollIntoViewIfNeeded()
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('morning-brief-settings-mobile.png') })
})

test('failed morning brief can be retried once and its real history opens the frozen brief for local feedback', async ({ page, request, project }) => {
  expect((await request.put(settingsPath(project.id), { data: settings('0', true) })).ok()).toBe(true)
  await reply(request, ' '); await due(project.id)
  await expect.poll(async () => (await (await request.get(historyPath(project.id))).json()).items[0]?.status).toBe('FAILED')
  expect((await request.put(settingsPath(project.id), { data: settings('1', false) })).ok()).toBe(true)
  await page.goto('/')
  const panel = page.getByRole('region', { name: '自动晨报', exact: true })
  await expect(panel).toContainText('生成失败')
  await reply(request, '没有运行样本，需要先执行测试。')
  let lost = false
  await page.route('**/morning-brief/occurrences/*/retry', async route => {
    if (!lost) { lost = true; const response = await route.fetch(); expect(response.ok()).toBe(true); await route.abort('failed') }
    else await route.continue()
  })
  await panel.getByRole('button', { name: '手工重试此晨报', exact: true }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  await page.reload()
  await panel.getByRole('button', { name: '确认上次重试结果', exact: true }).click()
  await expect(panel).toContainText('已生成')
  const history = (await (await request.get(historyPath(project.id))).json()).items[0]
  expect(history.attemptCount).toBe(2)
  expect(history.attempts.map((attempt: { status: string }) => attempt.status)).toEqual(['FAILED', 'SUCCEEDED'])
  await panel.getByRole('button', { name: '查看生成的简报', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '昨天的质量晨报', exact: true })
  const preview = detail.getByRole('region', { name: '质量简报预览', exact: true })
  await expect(preview).toContainText('指定日期范围的统计快照')
  await expect(preview).toContainText('暂无样本')
  const frozen = (await (await request.get(`/api/projects/${project.id}/assets/${history.assetId}`)).json()).data.metrics
  await detail.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 优化', exact: true })
  for (const round of [1, 2]) {
    expect((await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { content: `第 ${round} 轮人工反馈后的建议。` } } } })).ok()).toBe(true)
    await drawer.getByRole('textbox', { name: '本轮反馈', exact: true }).fill('改善正文，保持统计事实')
    await drawer.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(drawer.getByRole('alert')).toContainText(`至版本 ${round + 1}`)
  }
  expect((await (await request.get(`/api/projects/${project.id}/assets/${history.assetId}`)).json()).data.metrics).toEqual(frozen)
  expect((await (await request.get(historyPath(project.id))).json()).items[0].metrics).toEqual(frozen)
})
