import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    const project = await (await request.post('/api/projects', { data: { name: `步骤编辑-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    }
  },
})
async function create(request: APIRequestContext, projectId: string, type: string, name: string, data = {}, parentId?: string) {
  const response = await request.post(`/api/projects/${projectId}/assets`, { data: { type, name, data, parentId } })
  expect(response.ok()).toBe(true)
  return await response.json() as Asset
}

test('a UI step card edits DSL and accepts repeated local feedback without changing any of the other nineteen steps', async ({ page, request, project }) => {
  const scene = await create(request, project.id, 'UI_SCENARIO', '用户资料更新', { baseUrl: 'http://127.0.0.1:8082/__business/page' })
  const steps: Asset[] = []
  for (let index = 0; index < 20; index++) steps.push(await create(request, project.id, 'UI_STEP', `步骤 ${String(index + 1).padStart(2, '0')}`, { action: 'click', selector: `#button-${index}`, timeoutMs: 8000 }, scene.id))
  const target = steps[7]!
  await page.goto('/ui-tests')
  await page.getByRole('button', { name: `编辑 ${scene.name}`, exact: true }).click()
  let detail = page.getByRole('dialog', { name: scene.name, exact: true })
  await detail.getByRole('tab', { name: '独立子项 · 20', exact: true }).click()
  const cards = detail.getByRole('region', { name: '步骤编排', exact: true })
  await expect(cards.locator('[data-step-id]')).toHaveCount(20)
  await cards.locator(`[data-step-id="${target.id}"]`).getByRole('button', { name: `编辑 ${target.name}`, exact: true }).click()
  detail = page.getByRole('dialog', { name: target.name, exact: true })
  await detail.getByRole('tab', { name: '步骤 DSL', exact: true }).click()
  const dsl = detail.getByRole('textbox', { name: 'UI 步骤 DSL', exact: true })
  await dsl.fill(JSON.stringify({ id: steps[0]!.id, action: 'fill', selector: '#email', value: 'owner@example.test' }))
  await detail.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(detail.getByRole('alert').filter({ hasText: '不允许' })).toContainText('id')
  expect((await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json()).version).toBe('1')
  await dsl.fill(JSON.stringify({ action: 'fill', selector: '#email', value: 'owner@example.test' }))
  const patched = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${target.id}`))
  await detail.getByRole('button', { name: '保存修改', exact: true }).click()
  expect((await patched).status()).toBe(200)
  let latest = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
  expect(latest.data).toMatchObject({ action: 'fill', selector: '#email', value: 'owner@example.test', timeoutMs: 8000 })
  await detail.getByRole('button', { name: 'AI 优化当前记录', exact: true }).click()
  const ai = page.getByRole('dialog', { name: 'AI 优化', exact: true })
  for (const [instruction, selector] of [['定位器太脆弱，使用稳定的测试 ID', '[data-testid="email"]'], ['仍不满意，限定到用户资料表单', '#profile [data-testid="email"]']]) {
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { selector } } } })
    await ai.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(instruction!)
    await ai.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(ai.getByText(`已更新「${target.name}」至版本 ${Number(latest.version) + 1}，可以继续输入下一轮反馈。`, { exact: true })).toBeVisible()
    latest = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    expect(latest).toMatchObject({ id: target.id, parentId: scene.id, position: target.position, data: { selector, action: 'fill', value: 'owner@example.test', timeoutMs: 8000 } })
    const children = await (await request.get(`/api/projects/${project.id}/assets/${scene.id}/children`)).json() as Asset[]
    expect(children.filter(step => step.id !== target.id)).toEqual(steps.filter(step => step.id !== target.id))
    expect(await (await request.get(`/api/projects/${project.id}/assets/${scene.id}`)).json()).toEqual(scene)
  }
  await ai.locator('.arco-drawer-close-btn').click()
  await detail.getByRole('button', { name: `返回 ${scene.name}`, exact: true }).click()
  await expect(page.getByRole('region', { name: '步骤编排', exact: true }).locator(`[data-step-id="${target.id}"]`)).toContainText('#profile [data-testid="email"]')
})

test('a pending UI DSL draft retains its original version on refresh and cannot overwrite a concurrent edit', async ({ page, request, project }) => {
  const scene = await create(request, project.id, 'UI_SCENARIO', '草稿冲突场景')
  const step = await create(request, project.id, 'UI_STEP', '填写用户名', { action: 'fill', selector: '#name', value: 'original' }, scene.id)
  await page.goto('/ui-tests')
  await page.getByRole('button', { name: `编辑 ${scene.name}`, exact: true }).click()
  await page.getByRole('dialog', { name: scene.name, exact: true }).getByRole('tab', { name: '独立子项 · 1', exact: true }).click()
  await page.getByRole('region', { name: '步骤编排', exact: true }).getByRole('button', { name: `编辑 ${step.name}`, exact: true }).click()
  const detail = page.getByRole('dialog', { name: step.name, exact: true })
  await detail.getByRole('tab', { name: '步骤 DSL', exact: true }).click()
  const dsl = detail.getByRole('textbox', { name: 'UI 步骤 DSL', exact: true })
  const draft = '{"value":"我的未保存输入"}'
  await dsl.fill(draft)
  const other = await (await request.patch(`/api/projects/${project.id}/assets/${step.id}`, { data: { baseVersion: step.version, data: { selector: '#new-name', value: '另一同事输入' } } })).json() as Asset
  await detail.getByRole('button', { name: '刷新当前记录', exact: true }).click()
  await expect(detail.getByRole('alert').filter({ hasText: '你的未保存修改仍基于' })).toBeVisible()
  await expect(dsl).toHaveValue(draft)
  const conflicted = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${step.id}`))
  await detail.getByRole('button', { name: '保存修改', exact: true }).click()
  const response = await conflicted
  expect(response.status()).toBe(409)
  expect(response.request().postDataJSON().baseVersion).toBe('1')
  expect(await (await request.get(`/api/projects/${project.id}/assets/${step.id}`)).json()).toEqual(other)
  await detail.getByRole('button', { name: '保留我的修改并以最新版本继续', exact: true }).click()
  const saved = page.waitForResponse(result => result.request().method() === 'PATCH' && result.url().endsWith(`/assets/${step.id}`))
  await detail.getByRole('button', { name: '保存修改', exact: true }).click()
  expect((await saved).status()).toBe(200)
  expect((await (await request.get(`/api/projects/${project.id}/assets/${step.id}`)).json()).data).toMatchObject({ selector: '#new-name', value: '我的未保存输入' })
})
