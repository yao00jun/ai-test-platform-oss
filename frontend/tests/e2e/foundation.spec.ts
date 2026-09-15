import { expect, test } from '@playwright/test'
import type { Asset } from '../../src/api/types'

/** Real HTTP integration: a uniquely named temporary project, with scoped cleanup. No intercepted production API. */
test('project, independent child, field patch and version history survive reload', async ({ page, request }) => {
  const projectName = `前端验收-${crypto.randomUUID().slice(0, 8)}`
  let project: Asset | undefined
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  try {
    await page.goto('/projects')
    await expect(page.getByRole('heading', { name: '项目管理', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '新建项目', exact: true }).first().click()
    const modal = page.locator('.arco-modal-container[role="dialog"]')
    await modal.getByRole('textbox', { name: '名称', exact: true }).fill(projectName)
    await modal.getByRole('textbox', { name: '项目说明', exact: true }).fill('仅用于自动验收，完成后自动删除。')
    const createResponse = page.waitForResponse((response) => response.url().endsWith('/api/projects') && response.request().method() === 'POST')
    await modal.getByRole('button', { name: '创建', exact: true }).click()
    project = await (await createResponse).json() as Asset
    await expect(page.locator(`[data-project-id="${project.id}"]`)).toContainText(projectName)
    await page.getByRole('menuitem', { name: '测试用例', exact: true }).click()
    await page.getByRole('button', { name: '新建功能用例', exact: true }).first().click()
    await modal.getByRole('textbox', { name: '名称', exact: true }).fill('独立步骤验收')
    await modal.getByRole('textbox', { name: '前置条件', exact: true }).fill('用户已登录')
    await modal.getByRole('button', { name: '创建', exact: true }).click()
    const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: '独立步骤验收', exact: true }) })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '编辑 独立步骤验收', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '独立步骤验收', exact: true })
    await drawer.getByRole('tab', { name: /独立子项/ }).click()
    await drawer.getByRole('button', { name: '添加功能步骤', exact: true }).click()
    const activeModal = page.getByRole('dialog', { name: '新建功能步骤', exact: true })
    await activeModal.getByRole('textbox', { name: '名称', exact: true }).fill('步骤一')
    await activeModal.getByRole('textbox', { name: '测试步骤', exact: true }).fill('打开订单列表')
    await activeModal.getByRole('textbox', { name: '预期结果', exact: true }).fill('显示当前用户订单')
    await activeModal.getByRole('button', { name: '创建', exact: true }).click()
    await expect(drawer.getByRole('button', { name: '步骤一', exact: true })).toBeVisible()
    await drawer.getByRole('tab', { name: '详情与编辑', exact: true }).click()
    await drawer.getByRole('textbox', { name: '前置条件', exact: true }).fill('用户已登录，且存在有效订单')
    const patchResponse = page.waitForResponse((response) => response.request().method() === 'PATCH' && response.url().includes('/assets/'))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    const patch = (await patchResponse).request().postDataJSON() as { data: Record<string, unknown> }
    expect(patch.data).toEqual({ precondition: '用户已登录，且存在有效订单' })
    await drawer.getByRole('tab', { name: /独立子项/ }).click()
    await expect(drawer.getByRole('button', { name: '步骤一', exact: true })).toBeVisible()
    await drawer.getByRole('tab', { name: '版本历史', exact: true }).click()
    await expect(drawer.getByText('版本 2', { exact: false }).first()).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: '独立步骤验收', exact: true })).toBeVisible()
    expect(errors).toEqual([])
  } finally {
    if (project) {
      const current = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (current.ok()) {
        const latest = await current.json() as Asset
        const deleted = await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${encodeURIComponent(latest.version)}`)
        expect(deleted.status()).toBe(204)
      }
    }
  }
})
