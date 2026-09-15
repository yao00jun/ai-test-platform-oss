import { expect, test as base, type Locator } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset; drawer: Locator }>({
  project: async ({ page, request }, use) => {
    const project = await (await request.post('/api/projects', { data: { name: `数据映射-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    }
  },
  drawer: async ({ page, project }, use) => {
    expect(project.id).toBeTruthy()
    await page.goto('/scenarios')
    await page.getByRole('tab', { name: '数据集', exact: true }).click()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    await use(page.getByRole('dialog', { name: '导入与导出', exact: true }))
  },
})

test('portable datasets offer no transformations and typed CSV offers only lossless renaming', async ({ page, request, project, drawer }) => {
  const dataset = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'DATASET', name: '保真数据', data: { columns: ['id', 'payload'], rows: [{ id: '001', payload: { enabled: true } }] } } })).json() as Asset
  for (const format of ['json', 'csv']) {
    const exported = await request.post(`/api/projects/${project.id}/exports`, { data: { type: 'DATASET', assetIds: [dataset.id], format } })
    expect(exported.ok()).toBe(true)
    await drawer.getByLabel('选择导入文件', { exact: true }).setInputFiles({ name: `typed.${format}`, mimeType: 'application/octet-stream', buffer: await exported.body() })
    await drawer.getByRole('button', { name: '预览并检查', exact: true }).click()
    await expect(drawer.getByText('可以导入', { exact: true })).toBeVisible()
    if (format === 'json') await expect(drawer.locator('summary').filter({ hasText: '数据列' })).toHaveCount(0)
    else {
      await drawer.locator('summary').filter({ hasText: '数据列' }).click()
      await expect(drawer.getByLabel('列 id 的类型', { exact: true })).toHaveCount(0)
      await drawer.getByLabel('列 id 的映射名称', { exact: true }).fill('number')
      const changed = page.waitForResponse(response => response.url().endsWith('/imports/preview'))
      await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
      const preview = await (await changed).json()
      expect(preview.status).toBe('READY')
      expect(preview.nodes[0].data.rows).toEqual([{ number: '001', payload: { enabled: true } }])
    }
  }
})

test('ordinary CSV type choices transform values and unsupported saved choices can be cleared by reinspection', async ({ page, request, project, drawer }) => {
  await drawer.getByLabel('选择导入文件', { exact: true }).setInputFiles({ name: 'ordinary.csv', mimeType: 'text/csv', buffer: Buffer.from('id\r\n007\r\n') })
  await drawer.getByRole('button', { name: '预览并检查', exact: true }).click()
  await expect(drawer.getByText('可以导入', { exact: true })).toBeVisible()
  await drawer.locator('summary').filter({ hasText: '数据列' }).click()
  await drawer.getByLabel('列 id 的类型', { exact: true }).click()
  await page.getByText('INTEGER', { exact: true }).click()
  const converted = page.waitForResponse(response => response.url().endsWith('/imports/preview'))
  await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
  expect((await (await converted).json()).nodes[0].data.rows).toEqual([{ id: 7 }])

  const dataset = await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'DATASET', name: '旧预览', data: { columns: ['id'], rows: [{ id: '001' }] } } })).json() as Asset
  const exported = await request.post(`/api/projects/${project.id}/exports`, { data: { type: 'DATASET', assetIds: [dataset.id], format: 'csv' } })
  const invalid = await (await request.post(`/api/projects/${project.id}/imports/preview`, { multipart: { type: 'DATASET', format: 'csv', columnTypes: '{"id":"NUMBER"}', file: { name: 'typed.csv', mimeType: 'text/csv', buffer: await exported.body() } } })).json()
  expect(invalid.status).toBe('INVALID')
  await drawer.locator('summary').filter({ hasText: '继续已有导入预览' }).click()
  await drawer.getByLabel('导入预览 ID', { exact: true }).fill(invalid.id)
  await drawer.getByRole('button', { name: '打开预览', exact: true }).click()
  await expect(drawer.getByText('需要修正', { exact: true })).toBeVisible()
  const corrected = page.waitForResponse(response => response.url().endsWith('/imports/preview'))
  await drawer.getByRole('button', { name: '重新检查映射与文件', exact: true }).click()
  const preview = await (await corrected).json()
  expect(preview.status).toBe('READY')
  expect(preview.metadata.columnTypes).toEqual({})
  expect(preview.nodes[0].data.rows).toEqual([{ id: '001' }])
})
