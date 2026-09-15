import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const credentials = { username: 'workspace-owner', password: 'fixture-password-2026!' }
const runtime = new WeakMap<Page, { errors: string[]; warnings: string[]; console: string[] }>()
test.beforeEach(({ page }) => {
  const observed = { errors: [] as string[], warnings: [] as string[], console: [] as string[] }
  runtime.set(page, observed)
  page.on('pageerror', error => observed.errors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error' || message.type() === 'warning') observed.console.push(message.text())
    if (message.text().includes('[Vue warn]')) observed.warnings.push(message.text())
  })
})
test.afterEach(async ({ page }, testInfo) => {
  const observed = runtime.get(page)!
  await testInfo.attach('runtime-health', { body: JSON.stringify({ url: page.url(), title: await page.title(), ...observed }), contentType: 'application/json' })
  expect(observed.errors).toEqual([])
  expect(observed.warnings).toEqual([])
  const titles: Record<string, string> = { '/cases': '测试用例 · AI 测试平台', '/projects': '项目管理 · AI 测试平台' }
  const title = titles[new URL(page.url()).pathname]
  expect(title).toBeDefined()
  await expect(page).toHaveTitle(title!)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})
async function csrf(request: APIRequestContext) { return (await (await request.get('/api/auth/session')).json()).csrfToken as string }
async function apiLogin(request: APIRequestContext) {
  expect((await request.post('/api/auth/login', { form: credentials, headers: { 'X-CSRF-TOKEN': await csrf(request) } })).status()).toBe(200)
}
async function uiLogin(page: Page) {
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill(credentials.username)
  await page.getByLabel('密码', { exact: true }).fill(credentials.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录测试工作台', exact: true })).toBeHidden()
}
async function create(request: APIRequestContext, route: string, data: unknown) {
  const response = await request.post(route, { data, headers: { 'X-CSRF-TOKEN': await csrf(request) } })
  expect(response.status()).toBe(201)
  return await response.json() as Asset
}
async function cleanup(request: APIRequestContext, project: Asset) {
  await apiLogin(request)
  const latest = await (await request.get(`/api/projects/${project.id}/assets/${project.id}`)).json() as Asset
  expect((await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${latest.version}`, {
    headers: { 'X-CSRF-TOKEN': await csrf(request) },
  })).status()).toBe(204)
}

test('登录门控 protects deep links, reports wrong credentials, and supports explicit logout', async ({ page, request }, testInfo) => {
  const workspaceReads: string[] = []
  page.on('request', request => { if (/\/api\/(projects|catalog)$/.test(request.url())) workspaceReads.push(request.url()) })
  expect((await request.get('/api/projects')).status()).toBe(401)
  await page.goto('/cases')
  await expect(page.getByRole('heading', { name: '登录测试工作台', exact: true })).toBeVisible()
  expect(workspaceReads).toEqual([])
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill(credentials.username)
  await page.getByLabel('密码', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('用户名或密码不正确')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  await testInfo.attach('desktop-login', { body: await page.screenshot(), contentType: 'image/png' })
  await uiLogin(page)
  await expect(page.getByRole('heading', { name: '测试用例', exact: true })).toBeVisible()
  expect(new URL(page.url()).pathname).toBe('/cases')
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  const confirm = page.getByRole('dialog', { name: '退出登录', exact: true })
  await expect(confirm).toContainText('未保存')
  await expect(confirm.locator('.arco-modal')).toHaveCSS('opacity', '1')
  await expect(confirm.locator('.arco-modal-mask')).toHaveCSS('opacity', '1')
  await testInfo.attach('logout-confirmation', { body: await page.screenshot(), contentType: 'image/png' })
  await confirm.getByRole('button', { name: '取消', exact: true }).click()
  expect((await (await page.request.get('/api/auth/session')).json()).authenticated).toBe(true)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await confirm.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录测试工作台', exact: true })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect((await page.request.get('/api/projects')).status()).toBe(401)
})

test('expired session preserves an actual manual draft and requires a separate save after login', async ({ page }, testInfo) => {
  await apiLogin(page.request)
  const project = await create(page.request, '/api/projects', { name: `认证草稿-${crypto.randomUUID()}` })
  try {
    const route = `/api/projects/${project.id}/assets`
    const target = await create(page.request, route, { type: 'FUNCTIONAL_CASE', name: '会话过期保留人工编辑', data: { precondition: '原条件' } })
    const sibling = await create(page.request, route, { type: 'FUNCTIONAL_CASE', name: '保持不变的兄弟项', data: { precondition: '人工确认原文' } })
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    await page.goto('/cases')
    await page.getByRole('button', { name: `编辑 ${target.name}`, exact: true }).click()
    const drawer = page.getByRole('dialog', { name: target.name, exact: true })
    await drawer.getByRole('textbox', { name: '前置条件', exact: true }).fill('未保存的人工草稿，不可丢失')
    let writes = 0
    page.on('request', request => { if (request.method() === 'PATCH' && request.url().endsWith(`/assets/${target.id}`)) writes++ })
    expect((await page.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': await csrf(page.request) } })).status()).toBe(204)
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登录测试工作台', exact: true })).toBeVisible()
    await expect(page.getByText('当前标签页的未保存编辑已保留，登录后可继续。', { exact: true })).toBeVisible()
    await expect(drawer).toBeHidden()
    await page.keyboard.press('Tab')
    expect(await page.locator('.session-gate').evaluate(element => element.contains(document.activeElement))).toBe(true)
    await testInfo.attach('draft-reauthentication', { body: await page.screenshot(), contentType: 'image/png' })
    await uiLogin(page)
    await expect(drawer.getByRole('textbox', { name: '前置条件', exact: true })).toHaveValue('未保存的人工草稿，不可丢失')
    await testInfo.attach('restored-manual-draft', { body: await page.screenshot(), contentType: 'image/png' })
    expect(writes).toBe(1)
    const before = await (await page.request.get(`${route}/${target.id}`)).json() as Asset
    expect(before.version).toBe(target.version)
    const saved = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/assets/${target.id}`))
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click()
    expect((await saved).status()).toBe(200)
    expect(writes).toBe(2)
    expect((await (await page.request.get(`${route}/${target.id}`)).json()).data.precondition).toBe('未保存的人工草稿，不可丢失')
    expect(await (await page.request.get(`${route}/${sibling.id}`)).json()).toEqual(sibling)
  } finally { await cleanup(page.request, project) }
})

test('mobile login fits the viewport and opens a working workspace', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/projects')
  await expect(page.getByRole('heading', { name: '登录测试工作台', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await testInfo.attach('mobile-login', { body: await page.screenshot(), contentType: 'image/png' })
  await uiLogin(page)
  await expect(page.getByRole('heading', { name: '项目管理', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '打开导航', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await testInfo.attach('mobile-authenticated-navigation', { body: await page.screenshot(), contentType: 'image/png' })
})
