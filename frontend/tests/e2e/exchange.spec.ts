import { readFile } from 'node:fs/promises'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

async function setup(page: Page, request: APIRequestContext) {
  const response = await request.post('/api/projects', { data: { name: `文件交换验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
  expect(response.ok()).toBe(true)
  const project = await response.json() as Asset
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
  return project
}
async function cleanup(request: APIRequestContext, project?: Asset) {
  if (!project) return
  const current = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
  if (current.ok()) {
    const asset = await current.json() as Asset
    expect((await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${asset.version}`)).status()).toBe(204)
  }
}

test('native template download, atomic import, saved preview and selected export preserve hierarchy', async ({ page, request }) => {
  let project: Asset | undefined
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  try {
    project = await setup(page, request)
    await page.goto('/cases')
    await page.getByRole('button', { name: '导入模板', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '导入与导出', exact: true })
    const templateEvent = page.waitForEvent('download', { timeout: 15000 })
    await drawer.getByRole('button', { name: '下载 功能测试用例 json 模板', exact: true }).click()
    const download = await templateEvent
    expect(await download.failure()).toBeNull()
    const template = await readFile((await download.path())!)
    expect(JSON.parse(template.toString()).nodes).toHaveLength(5)
    await drawer.getByRole('tab', { name: '导入资产', exact: true }).click()
    await drawer.getByLabel('选择导入文件').setInputFiles({ name: download.suggestedFilename(), mimeType: 'application/json', buffer: template })
    await drawer.getByRole('button', { name: '预览并检查', exact: true }).click()
    await expect(drawer.getByText('可以导入', { exact: true })).toBeVisible()
    await expect(drawer.getByRole('table', { name: '导入预览内容' }).locator('tbody tr')).toHaveCount(5)
    await drawer.getByRole('button', { name: '确认导入 5 条资产', exact: true }).click()
    await expect(drawer.getByText('已保存 5 条资产。重复打开此预览不会再次创建。', { exact: true })).toBeVisible()
    const assets = await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()
    expect(assets.total).toBe(1)
    await page.reload()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    await drawer.locator('summary').filter({ hasText: '继续已有导入预览' }).click()
    await drawer.getByRole('button', { name: '打开预览', exact: true }).click()
    await expect(drawer.getByText('已保存 5 条资产。重复打开此预览不会再次创建。', { exact: true })).toBeVisible()
    await page.reload()
    await page.getByLabel('选中 已注册用户可以登录', { exact: true }).check()
    await page.getByRole('button', { name: '导出 · 1', exact: true }).click()
    const exportEvent = page.waitForEvent('download', { timeout: 15000 })
    await drawer.getByRole('button', { name: '下载导出文件', exact: true }).click()
    const exported = await exportEvent
    expect(await exported.failure()).toBeNull()
    const result = JSON.parse(await readFile((await exported.path())!, 'utf8'))
    expect(result.nodes).toHaveLength(5)
    expect(result.nodes.filter((node: { type: string }) => node.type === 'FUNCTIONAL_STEP')).toHaveLength(2)
    expect(errors).toEqual([])
  } finally { await cleanup(request, project) }
})

test('DDT mapping is restored after reopening and clearing it returns to original column names', async ({ page, request }) => {
  let project: Asset | undefined
  try {
    project = await setup(page, request)
    await page.goto('/scenarios')
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '导入与导出', exact: true })
    await drawer.getByLabel('选择导入文件').setInputFiles({ name: 'mapping.csv', mimeType: 'text/csv', buffer: Buffer.from('编号,数量\r\n001,2\r\n') })
    await drawer.getByRole('button', { name: '预览并检查', exact: true }).click()
    await expect(drawer.getByText('可以导入', { exact: true })).toBeVisible()
    await drawer.locator('summary').filter({ hasText: '数据列名称与类型' }).click()
    await drawer.getByLabel('列 编号 的映射名称', { exact: true }).fill('id')
    await expect(drawer.getByRole('button', { name: '确认导入 1 条资产', exact: true })).toBeDisabled()
    await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
    await expect(drawer.getByRole('button', { name: '确认导入 1 条资产', exact: true })).toBeEnabled()
    await page.reload()
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    await drawer.locator('summary').filter({ hasText: '继续已有导入预览' }).click()
    await drawer.getByRole('button', { name: '打开预览', exact: true }).click()
    await drawer.locator('summary').filter({ hasText: '数据列名称与类型' }).click()
    await expect(drawer.getByLabel('列 编号 的映射名称', { exact: true })).toHaveValue('id')
    await drawer.getByLabel('列 编号 的映射名称', { exact: true }).fill('')
    await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
    await expect(drawer.getByRole('button', { name: '确认导入 1 条资产', exact: true })).toBeEnabled()
    await drawer.getByRole('button', { name: '确认导入 1 条资产', exact: true }).click()
    await expect(drawer.getByText('已保存 1 条资产。重复打开此预览不会再次创建。', { exact: true })).toBeVisible()
    const response = await request.get(`/api/projects/${project.id}/assets?type=DATASET`)
    const dataset = (await response.json()).items[0]
    expect(dataset.data.columns).toEqual(['编号', '数量'])
    expect(dataset.data.rows).toEqual([{ 编号: '001', 数量: '2' }])
  } finally { await cleanup(request, project) }
})
