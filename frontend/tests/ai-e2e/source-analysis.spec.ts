import { expect, test as base, type APIRequestContext } from '@playwright/test'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ source: { project: Asset; root: string; backend: string; frontend: string; ddl: string } }>({
  source: async ({ page, request }, use) => {
    const root = await mkdtemp(path.join(tmpdir(), 'aitest-源码-'))
    const backend = path.join(root, '后端'), frontend = path.join(root, '前端'), ddl = path.join(root, '业务表.sql')
    await mkdir(backend); await mkdir(frontend)
    await writeFile(path.join(backend, 'Orders.java'), 'package shop;\n@RestController @RequestMapping("/orders")\nclass Orders {\n  @PostMapping("/refund") void refund(int amount) {\n    if (amount < 1) throw new IllegalArgumentException("amount must be positive");\n  }\n}')
    await writeFile(path.join(frontend, 'Refund.vue'), '<template><button data-testid="submit-refund">退款</button></template>')
    await writeFile(ddl, 'CREATE TABLE t_order(id BIGINT PRIMARY KEY, amount DECIMAL(10,2) NOT NULL);')
    const project = await (await request.post('/api/projects', { data: { name: `源码验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use({ project, root, backend, frontend, ddl }) } finally {
      await removeProject(request, project.id)
      await removeSources(root); expect(errors).toEqual([])
    }
  },
})
async function removeSources(root: string) {
  if (path.dirname(root) !== path.resolve(tmpdir()) || !path.basename(root).startsWith('aitest-源码-')) throw new Error('Unexpected temporary source directory')
  await rm(root, { recursive: true, force: true })
}
async function removeProject(request: APIRequestContext, id: string) {
  const list = await request.get(`/api/projects/${id}/source-analyses`)
  if (list.ok()) for (const item of (await list.json()).items) if (!['READY', 'PARTIAL', 'CANCELLED', 'FAILED', 'INTERRUPTED'].includes(item.status)) await request.post(`/api/projects/${id}/source-analyses/${item.id}/cancel`, { data: {} })
  const current = await request.get(`/api/projects/${id}/assets/${id}`)
  if (current.ok()) await request.delete(`/api/projects/${id}/assets/${id}?baseVersion=${(await current.json()).version}`)
}

test('project source inputs produce actual Java, UI and DDL evidence and historical code survives source edits', async ({ page, request, source }, testInfo) => {
  await page.goto('/projects')
  const panel = page.getByRole('region', { name: '五类输入与源码证据', exact: true })
  await panel.getByLabel('后端源码路径', { exact: true }).fill(source.backend)
  await panel.getByLabel('前端源码路径', { exact: true }).fill(source.frontend)
  await panel.getByLabel('SQL 脚本路径', { exact: true }).fill(source.ddl)
  await panel.getByRole('button', { name: '保存项目源码设置', exact: true }).click()
  await expect(panel.getByText('源码设置已保存', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '分析源码与 DDL', exact: true }).click()
  await expect(panel.getByTestId('source-analysis-status')).toHaveText('分析完整', { timeout: 45000 })
  await expect(panel).toContainText('POST /orders/refund')
  await expect(panel).toContainText('amount < 1')
  await expect(panel).toContainText('testId=submit-refund')
  await expect(panel).toContainText('t_order')
  await panel.getByRole('button', { name: '查看源码 BACKEND / Orders.java', exact: true }).click()
  await expect(panel.getByTestId('source-content')).toContainText('amount < 1')
  const analysis = (await (await request.get(`/api/projects/${source.project.id}/source-analyses`)).json()).items[0]
  await writeFile(path.join(source.backend, 'Orders.java'), 'class ChangedAfterCapture {}')
  await page.reload()
  await panel.getByRole('button', { name: '查看源码 BACKEND / Orders.java', exact: true }).click()
  await expect(panel.getByTestId('source-content')).toContainText('amount < 1')
  expect(await readFile(path.join(source.backend, 'Orders.java'), 'utf8')).toContain('ChangedAfterCapture')
  expect((await (await request.get(`/api/projects/${source.project.id}/source-analyses/${analysis.id}`)).json()).manifestHash).toBe(analysis.manifestHash)
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('source-analysis-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => panel.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(320)
  await panel.getByRole('heading', { name: '五类输入与源码证据', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('source-analysis-mobile.png') })
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('an uncertain source submission reuses its exact input after reload and pending configuration retains its CAS version', async ({ page, request, source }) => {
  const submissions: unknown[] = [], ids: string[] = []
  await page.route(`**/api/projects/${source.project.id}/source-analyses`, async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    submissions.push(route.request().postDataJSON())
    const response = await route.fetch(); expect(response.ok()).toBe(true)
    ids.push((await response.json()).analysisId)
    if (submissions.length === 1) await route.abort('connectionreset'); else await route.fulfill({ response })
  })
  await page.goto('/projects')
  const panel = page.getByRole('region', { name: '五类输入与源码证据', exact: true })
  await panel.getByLabel('后端源码路径', { exact: true }).fill(source.backend)
  await panel.getByLabel('补充 DDL', { exact: true }).fill('CREATE TABLE recovery(id INT);')
  await panel.getByRole('button', { name: '分析源码与 DDL', exact: true }).click()
  await expect(panel.getByRole('alert').filter({ hasText: '无法连接服务' })).toBeVisible()
  await page.reload()
  await expect(panel.getByLabel('后端源码路径', { exact: true })).toHaveValue(source.backend)
  await panel.getByRole('button', { name: '恢复源码分析提交', exact: true }).click()
  await expect(panel.getByTestId('source-analysis-status')).toHaveText('分析完整', { timeout: 45000 })
  expect(submissions).toHaveLength(2); expect(submissions[1]).toEqual(submissions[0]); expect(new Set(ids).size).toBe(1)
  expect((await (await request.get(`/api/projects/${source.project.id}/source-analyses`)).json()).total).toBe(1)
  expect((await request.patch(`/api/projects/${source.project.id}/assets/${source.project.id}`, { data: { baseVersion: source.project.version, data: { description: '同事已更新', frontendRepoPath: source.frontend } } })).ok()).toBe(true)
  await page.getByRole('button', { name: '刷新项目', exact: true }).click()
  await expect(panel.getByLabel('后端源码路径', { exact: true })).toHaveValue(source.backend)
  await panel.getByRole('button', { name: '保存项目源码设置', exact: true }).click()
  await expect(panel.getByRole('alert').filter({ hasText: '已被修改' })).toBeVisible()
  await panel.getByRole('button', { name: '保留我的路径修改并使用最新版本', exact: true }).click()
  await expect(panel.getByLabel('前端源码路径', { exact: true })).toHaveValue(source.frontend)
  await panel.getByRole('button', { name: '保存项目源码设置', exact: true }).click()
  await expect(panel.getByText('源码设置已保存', { exact: true })).toBeVisible()
  const saved = await (await request.get(`/api/projects/${source.project.id}/assets/${source.project.id}`)).json() as Asset
  expect(saved.data).toMatchObject({ backendRepoPath: source.backend, frontendRepoPath: source.frontend, description: '同事已更新' })
})

test('late analysis responses stay in the original project while switching workspaces', async ({ page, request, source }) => {
  const other = await (await request.post('/api/projects', { data: { name: `其他源码-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  let release: (() => void) | undefined, returned = false
  try {
    await page.route(`**/api/projects/${source.project.id}/source-analyses`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const response = await route.fetch()
      await new Promise<void>(resolve => { release = resolve })
      await route.fulfill({ response }); returned = true
    })
    await page.goto('/projects')
    const panel = page.getByRole('region', { name: '五类输入与源码证据', exact: true })
    await panel.getByLabel('后端源码路径', { exact: true }).fill(source.backend)
    await panel.getByRole('button', { name: '分析源码与 DDL', exact: true }).click()
    await expect.poll(() => !!release).toBe(true)
    await page.locator(`[data-project-id="${other.id}"]`).getByRole('button', { name: '进入项目', exact: true }).click()
    release!(); await expect.poll(() => returned).toBe(true)
    await expect(panel.getByLabel('后端源码路径', { exact: true })).toHaveValue('')
    await expect(panel.getByTestId('source-analysis-status')).toHaveCount(0)
    expect((await (await request.get(`/api/projects/${other.id}/source-analyses`)).json()).total).toBe(0)
    await page.locator(`[data-project-id="${source.project.id}"]`).getByRole('button', { name: '进入项目', exact: true }).click()
    await expect(panel.getByTestId('source-analysis-status')).toHaveText('分析完整', { timeout: 45000 })
    expect((await (await request.get(`/api/projects/${source.project.id}/source-analyses`)).json()).total).toBe(1)
  } finally { release?.(); await removeProject(request, other.id) }
})

test('creating a project preserves all source settings entered in the standard asset dialog', async ({ page, request, source }) => {
  let created: Asset | undefined
  try {
    await page.goto('/projects')
    await page.getByRole('button', { name: '新建项目', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新建项目', exact: true })
    await dialog.getByRole('textbox', { name: '名称', exact: true }).fill(`创建带源码-${crypto.randomUUID().slice(0, 8)}`)
    await dialog.getByRole('textbox', { name: '后端源码路径或 Git URL', exact: true }).fill(source.backend)
    await dialog.getByRole('textbox', { name: '前端源码路径或 Git URL', exact: true }).fill(source.frontend)
    await dialog.getByRole('textbox', { name: '数据库 DDL 路径', exact: true }).fill(source.ddl)
    const accepted = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/projects')
    await dialog.getByRole('button', { name: '创建', exact: true }).click()
    created = await (await accepted).json() as Asset
    await expect(dialog).toBeHidden()
    const panel = page.getByRole('region', { name: '五类输入与源码证据', exact: true })
    await expect(panel.getByLabel('后端源码路径', { exact: true })).toHaveValue(source.backend)
    expect(created.data).toMatchObject({ backendRepoPath: source.backend, frontendRepoPath: source.frontend, sqlScriptPath: source.ddl })
  } finally { if (created) await removeProject(request, created.id) }
})
