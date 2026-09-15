import { expect, test as base, type APIRequestContext, type Locator } from '@playwright/test'
import type { AcceptedJob, Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset; drawer: Locator }>({
  project: async ({ page, request }, use) => {
    expect((await request.post('http://127.0.0.1:8082/__fixture/reset')).ok()).toBe(true)
    const project = await (await request.post('/api/projects', { data: { name: `生成会话-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
    await page.addInitScript(id => localStorage.setItem('ai-test-platform:selected-project', id), project.id)
    try { await use(project) } finally {
      const latest = await request.get(`/api/projects/${project.id}/assets/${project.id}`)
      if (latest.ok()) await request.delete(`/api/projects/${project.id}/assets/${project.id}?baseVersion=${(await latest.json()).version}`)
    }
  },
  drawer: async ({ page, project }, use) => {
    expect(project.id).toBeTruthy()
    await page.goto('/')
    await page.getByRole('button', { name: 'AI 生成', exact: true }).click()
    await use(page.getByRole('dialog', { name: 'AI 生成', exact: true }))
  },
})
async function queue(request: APIRequestContext) {
  await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { changes: [{ operation: 'ADD', targetType: 'QUALITY_BRIEF', localKey: 'brief', name: '生成简报', data: { content: '普通内容' } }] } } })
}
async function submit(drawer: Locator, text: string) {
  await drawer.getByRole('textbox', { name: '生成要求', exact: true }).fill(text)
  await drawer.getByRole('button', { name: '开始生成', exact: true }).click()
}

test('new generation conversation starts independent server history', async ({ page, request, project, drawer }) => {
  await queue(request)
  const oldResponse = page.waitForResponse(response => response.url().endsWith('/api/ai/generate'))
  await submit(drawer, '第一会话独有意见')
  const old = await (await oldResponse).json() as AcceptedJob
  await expect(drawer.getByText('生成已完成，测试资产列表已刷新。', { exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: '新会话', exact: true }).click()
  await queue(request)
  const nextResponse = page.waitForResponse(response => response.url().endsWith('/api/ai/generate'))
  await submit(drawer, '全新会话意见')
  const next = await (await nextResponse).json() as AcceptedJob
  await expect(drawer.getByText('生成已完成，测试资产列表已刷新。', { exact: true })).toBeVisible()
  expect(next.conversationId).not.toBe(old.conversationId)
  const history = await (await request.get(`/api/ai/conversations/${next.conversationId}?projectId=${project.id}`)).json()
  expect(history.messages.filter((message: { role: string }) => message.role === 'user').map((message: { content: string }) => message.content)).toEqual(['全新会话意见'])
  const requests = await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()
  expect(JSON.stringify(requests.at(-1))).not.toContain('第一会话独有意见')
})

test('retry after a lost generation acknowledgement reuses the job without creating another asset', async ({ page, request, project, drawer }) => {
  await queue(request); await queue(request)
  const acknowledgements: AcceptedJob[] = []
  await page.route('**/api/ai/generate', async route => {
    const response = await route.fetch()
    acknowledgements.push(await response.json() as AcceptedJob)
    if (acknowledgements.length === 1) await route.abort('failed')
    else await route.fulfill({ response })
  })
  await submit(drawer, '只生成一次')
  await expect(drawer.getByRole('alert')).toContainText('无法连接服务')
  await expect.poll(async () => (await (await request.get(`/api/jobs/${acknowledgements[0]!.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await drawer.getByRole('button', { name: '开始生成', exact: true }).click()
  await expect.poll(() => acknowledgements.length).toBe(2)
  expect(acknowledgements[1]!.jobId).toBe(acknowledgements[0]!.jobId)
  await expect(drawer.getByText('生成已完成，测试资产列表已刷新。', { exact: true })).toBeVisible()
  const assets = await (await request.get(`/api/projects/${project.id}/assets?type=QUALITY_BRIEF`)).json()
  expect(assets.total).toBe(1)
  expect(await (await request.get('http://127.0.0.1:8082/__fixture/requests')).json()).toHaveLength(1)
})
