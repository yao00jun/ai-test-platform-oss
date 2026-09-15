import { expect, test } from '@playwright/test'
import type { Asset } from '../../src/api/types'

test('global feedback preserves current human assets across selected adoption, conflict and rejection', async ({ page, request }) => {
  const created = await request.post('/api/projects', { data: { name: `多轮反馈验收-${crypto.randomUUID().slice(0, 8)}`, data: {} } })
  expect(created.ok()).toBe(true)
  const project = await created.json() as Asset
  const make = async (name: string) => (await (await request.post(`/api/projects/${project.id}/assets`, { data: { type: 'FUNCTIONAL_CASE', name, data: { precondition: '原始前置条件', priority: 'P1' } } })).json()) as Asset
  try {
    const target = await make('退款检查'), sibling = await make('保持不变')
    const protectedAsset = await (await request.patch(`/api/projects/${project.id}/assets/${sibling.id}`, { data: { baseVersion: sibling.version, confirmed: true } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/cases')
    await expect(page.getByRole('button', { name: '全局反馈', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '全局 AI 反馈', exact: true })
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [
      { operation: 'MODIFY', targetType: 'FUNCTIONAL_CASE', targetId: target.id, baseVersion: target.version, data: { precondition: '包含优惠券退款' } },
      { operation: 'ADD', targetType: 'FUNCTIONAL_CASE', localKey: 'extra', name: '不采纳的候选', data: { priority: 'P2' } },
    ] } } })
    await drawer.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill('补充优惠券退款，保留已确认用例')
    await drawer.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(drawer.getByRole('heading', { name: '候选变更', exact: true })).toBeVisible()
    const targetChange = drawer.locator('[data-change-operation="MODIFY"]').filter({ hasText: '退款检查' })
    await targetChange.getByLabel('选择修改 退款检查', { exact: true }).check()
    await drawer.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
    await expect(drawer.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
    const current = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    expect(current.data.precondition).toBe('包含优惠券退款')
    expect((await (await request.get(`/api/projects/${project.id}/assets?type=FUNCTIONAL_CASE`)).json()).total).toBe(2)
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(protectedAsset)

    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [
      { operation: 'MODIFY', targetType: 'FUNCTIONAL_CASE', targetId: current.id, baseVersion: current.version, data: { precondition: '退款时间限制为七天' } },
    ] } } })
    await drawer.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill('继续限制退款时间')
    await drawer.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(drawer.getByText('退款时间限制为七天', { exact: true })).toBeVisible()
    await request.patch(`/api/projects/${project.id}/assets/${target.id}`, { data: { baseVersion: current.version, data: { precondition: '人工补充审批要求' } } })
    await drawer.locator('[data-change-operation="MODIFY"]').getByLabel('选择修改 退款检查', { exact: true }).check()
    await drawer.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
    await expect(drawer.getByRole('alert').filter({ hasText: '选定变更存在冲突' })).toBeVisible()
    expect((await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json()).data.precondition).toBe('人工补充审批要求')
    await drawer.getByRole('button', { name: '拒绝本轮候选', exact: true }).click()
    await expect(drawer.getByText('本轮候选已拒绝', { exact: true })).toBeVisible()
    const latest = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [
      { operation: 'MODIFY', targetType: 'FUNCTIONAL_CASE', targetId: latest.id, baseVersion: latest.version, data: { remark: '仅补充提醒，保留人工审批条件' } },
    ] } } })
    await drawer.getByRole('textbox', { name: '全局反馈意见', exact: true }).fill('保留人工审批条件，只补充备注')
    await drawer.getByRole('button', { name: '生成改进候选', exact: true }).click()
    await expect(drawer.getByText('仅补充提醒，保留人工审批条件', { exact: true })).toBeVisible()
    await drawer.locator('[data-change-operation="MODIFY"]').getByLabel('选择修改 退款检查', { exact: true }).check()
    await drawer.getByRole('button', { name: '采纳选中 1 项', exact: true }).click()
    await expect(drawer.getByText('已采纳 1 项变更', { exact: true })).toBeVisible()
    const final = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    expect(final.data).toMatchObject({ precondition: '人工补充审批要求', remark: '仅补充提醒，保留人工审批条件' })
    const modelRequests = await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()
    expect(JSON.stringify(modelRequests.at(-1))).toContain('人工补充审批要求')
    await page.reload()
    await page.getByRole('button', { name: '全局反馈', exact: true }).click()
    await expect(drawer.getByRole('region', { name: '候选变更预览', exact: true })).toContainText('已采纳')
    await drawer.getByText('多轮反馈记录 · 3 轮', { exact: true }).click()
    await expect(drawer.getByRole('log', { name: 'AI 对话记录' })).toContainText('补充优惠券退款，保留已确认用例')
    await expect(drawer.getByRole('log', { name: 'AI 对话记录' })).toContainText('保留人工审批条件，只补充备注')
  } finally {
    const current = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
    if (current.ok()) { const asset = await current.json() as Asset; await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${asset.version}`) }
  }
})
