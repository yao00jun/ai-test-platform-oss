import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

async function setup(page: Page, request: APIRequestContext) {
  const project = await (await request.post('/api/projects', { data: { name: `数据表格-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
  const dataset = await (await request.post(`/api/projects/${project.id}/assets`, { data: {
    type: 'DATASET', name: '订单驱动数据', data: {
      columns: ['orderNo', 'qty', 'enabled', 'meta', 'optional'],
      rows: [{ orderNo: '0012', qty: 1, enabled: true, meta: { tags: ['a'] }, optional: null }, { orderNo: '0090', qty: 2, enabled: false, meta: ['原始数组'] }],
    },
  } })).json() as Asset
  await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
  await page.goto('/scenarios')
  await page.getByRole('tab', { name: '数据集', exact: true }).click()
  await page.getByRole('button', { name: '编辑 订单驱动数据', exact: true }).click()
  return { project, dataset, drawer: page.getByRole('dialog', { name: '订单驱动数据', exact: true }) }
}
async function cleanup(request: APIRequestContext, project: Asset) {
  const current = await (await request.get(`/api/projects/${project.id}/assets/${project.id}`)).json() as Asset
  expect((await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${current.version}`)).status()).toBe(204)
}

test('the dataset grid edits typed cells and row/column order without losing leading zeros, nulls or nested values', async ({ page, request }) => {
  const { project, dataset, drawer } = await setup(page, request)
  try {
    const sibling = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'DATASET', name: '保持不变', data: { columns: ['name'], rows: [{ name: '另一数据集' }] } } })).json() as Asset
    const grid = drawer.getByRole('region', { name: '数据集表格', exact: true })
    await grid.getByRole('button', { name: '编辑第 1 行 qty', exact: true }).click()
    await grid.getByLabel('单元格内容', { exact: true }).fill('2.5')
    await grid.getByRole('button', { name: '应用单元格', exact: true }).click()
    await grid.getByLabel('新列名称', { exact: true }).fill('region')
    await grid.getByRole('button', { name: '添加列', exact: true }).click()
    await grid.getByLabel('管理数据列', { exact: true }).selectOption('qty')
    await grid.getByLabel('修改列名称', { exact: true }).fill('amount')
    await grid.getByRole('button', { name: '重命名列', exact: true }).click()
    await grid.getByRole('button', { name: '上移第 2 行', exact: true }).click()
    await grid.getByRole('button', { name: '编辑第 1 行 enabled', exact: true }).click()
    await grid.getByLabel('单元格内容', { exact: true }).fill('true')
    const saved = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${dataset.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    expect((await saved).status()).toBe(200)
    const latest = await (await request.get(`/api/projects/${project.id}/assets/${dataset.id}`)).json() as Asset
    expect(latest.id).toBe(dataset.id)
    expect(latest.data).toMatchObject({
      columns: ['orderNo', 'amount', 'enabled', 'meta', 'optional', 'region'],
      rows: [{ orderNo: '0090', amount: 2, enabled: true, meta: ['原始数组'] }, { orderNo: '0012', amount: 2.5, enabled: true, meta: { tags: ['a'] }, optional: null }],
    })
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(sibling)
    await grid.getByRole('tab', { name: 'JSON 编辑', exact: true }).click()
    const rowsText = await grid.getByRole('textbox', { name: '数据行', exact: true }).inputValue()
    expect(JSON.parse(rowsText)).toEqual(latest.data.rows)
    const exported = await request.post(`/api/projects/${project.id}/exports`, { data: { type: 'DATASET', assetIds: [dataset.id], format: 'json' } })
    expect(exported.ok()).toBe(true)
    const inspected = await (await request.post(`/api/projects/${project.id}/imports/preview`, { multipart: { type: 'DATASET', format: 'json', file: { name: 'edited-data.json', mimeType: 'application/json', buffer: await exported.body() } } })).json()
    expect(inspected.nodes[0].data.rows).toEqual(latest.data.rows)
    await page.reload()
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '编辑 订单驱动数据', exact: true }).click()
    await expect(grid.getByRole('button', { name: '编辑第 1 行 orderNo', exact: true })).toHaveText('0090')
  } finally { await cleanup(request, project) }
})

test('an unapplied grid cell retains its old CAS version through refresh and invalid typed values never save', async ({ page, request }) => {
  const { project, dataset, drawer } = await setup(page, request)
  try {
    const grid = drawer.getByRole('region', { name: '数据集表格', exact: true })
    await grid.getByRole('button', { name: '编辑第 1 行 qty', exact: true }).click()
    await grid.getByLabel('单元格内容', { exact: true }).fill('not-a-number')
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    await expect(grid.getByRole('alert')).toContainText('有效数字')
    expect((await (await request.get(`/api/projects/${project.id}/assets/${dataset.id}`)).json()).version).toBe('1')
    await grid.getByLabel('单元格内容', { exact: true }).fill('7')
    const other = await (await request.patch(`/api/projects/${project.id}/assets/${dataset.id}`, { data: { baseVersion: '1', data: { rows: [{ orderNo: '同事修改', qty: 5 }] } } })).json() as Asset
    await drawer.getByRole('button', { name: '刷新当前记录', exact: true }).click()
    await expect(drawer.getByRole('alert').filter({ hasText: '你的未保存修改仍基于' })).toBeVisible()
    await expect(grid.getByLabel('单元格内容', { exact: true })).toHaveValue('7')
    const conflicted = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${dataset.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    const response = await conflicted
    expect(response.status()).toBe(409)
    expect(response.request().postDataJSON().baseVersion).toBe('1')
    expect(await (await request.get(`/api/projects/${project.id}/assets/${dataset.id}`)).json()).toEqual(other)
    await drawer.getByRole('button', { name: '保留我的修改并以最新版本继续', exact: true }).click()
    const resolved = page.waitForResponse(result => result.request().method() === 'PATCH' && result.url().endsWith(`/assets/${dataset.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    expect((await resolved).status()).toBe(200)
    const latest = await (await request.get(`/api/projects/${project.id}/assets/${dataset.id}`)).json() as Asset
    expect(latest.version).toBe('3')
    expect(latest.data.rows).toEqual([{ orderNo: '0012', qty: 7, enabled: true, meta: { tags: ['a'] }, optional: null }, { orderNo: '0090', qty: 2, enabled: false, meta: ['原始数组'] }])
  } finally { await cleanup(request, project) }
})
