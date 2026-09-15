import { expect, test as base, type APIRequestContext } from '@playwright/test'
import type { Asset } from '../../src/api/types'

const test = base.extend<{ project: Asset }>({
  project: async ({ page, request }, use) => {
    const project = await (await request.post('/api/projects', { data: { name: `用例视图-${crypto.randomUUID().slice(0, 8)}`, data: {} } })).json() as Asset
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

test('module navigation and mindmap keep case identities through manual rename, reorder and three feedback rounds', async ({ page, request, project }) => {
  const orders = await create(request, project.id, 'MODULE', '交易')
  const refunds = await create(request, project.id, 'MODULE', '退款', {}, orders.id)
  const target = await create(request, project.id, 'FUNCTIONAL_CASE', '退款校验', { precondition: '<p>人工条件：订单已支付</p>' }, refunds.id)
  const sibling = await create(request, project.id, 'FUNCTIONAL_CASE', '金额校验', {}, refunds.id)
  const loose = await create(request, project.id, 'FUNCTIONAL_CASE', '未分类用例')
  const step = await create(request, project.id, 'FUNCTIONAL_STEP', '发起退款', { step: '<p>输入退款金额</p>', expected: '<p>显示到账时间</p>' }, target.id)
  await page.goto('/cases')
  const tree = page.getByRole('navigation', { name: '用例分类', exact: true })
  await tree.getByRole('button', { name: '筛选模块 退款', exact: true }).click()
  await expect(page.getByRole('table', { name: '测试资产列表', exact: true })).not.toContainText('未分类用例')
  await page.getByRole('tab', { name: '脑图视图', exact: true }).click()
  const map = page.getByRole('region', { name: '用例脑图', exact: true })
  const targetNode = map.locator(`[data-asset-id="${target.id}"]`)
  await expect(map).toContainText('交易 / 退款')
  await targetNode.getByRole('button', { name: target.name, exact: true }).dblclick()
  await targetNode.getByRole('textbox', { name: `重命名 ${target.name}`, exact: true }).fill('人工退款校验')
  await targetNode.getByRole('textbox', { name: `重命名 ${target.name}`, exact: true }).press('Enter')
  await expect(targetNode.getByRole('button', { name: '人工退款校验', exact: true })).toBeVisible()
  const reordered = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/assets/reorder'))
  await targetNode.getByRole('button', { name: '调整 人工退款校验 顺序，按上或下方向键移动', exact: true }).press('ArrowDown')
  expect((await reordered).ok()).toBe(true)
  const before = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
  const siblingBefore = await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()
  await targetNode.getByRole('button', { name: 'AI 优化 人工退款校验', exact: true }).click()
  const ai = page.getByRole('dialog', { name: 'AI 优化', exact: true })
  let latest = before
  for (const [feedback, remark] of [['补充退款渠道', '原路退款'], ['仍不满意，补充时间', '原路退款，七天内到账'], ['保留前置条件，补充异常分支', '原路退款，七天内到账；失败时提示人工处理']]) {
    await request.post('http://127.0.0.1:8082/__fixture/queue', { data: { content: { data: { remark } } } })
    await ai.getByRole('textbox', { name: '本轮反馈', exact: true }).fill(feedback!)
    await ai.getByRole('button', { name: '提交反馈', exact: true }).click()
    await expect(ai.getByText(`已更新「人工退款校验」至版本 ${Number(latest.version) + 1}，可以继续输入下一轮反馈。`, { exact: true })).toBeVisible()
    latest = await (await request.get(`/api/projects/${project.id}/assets/${target.id}`)).json() as Asset
    expect(latest).toMatchObject({ id: target.id, parentId: refunds.id, position: before.position, name: '人工退款校验', data: { precondition: '<p>人工条件：订单已支付</p>', remark } })
    expect(await (await request.get(`/api/projects/${project.id}/assets/${sibling.id}`)).json()).toEqual(siblingBefore)
    expect(await (await request.get(`/api/projects/${project.id}/assets/${step.id}`)).json()).toEqual(step)
    expect(await (await request.get(`/api/projects/${project.id}/assets/${loose.id}`)).json()).toEqual(loose)
  }
  await ai.locator('.arco-drawer-close-btn').click()
  await expect(targetNode).toContainText(`v${latest.version}`)
  await expect(targetNode).toContainText('失败时提示人工处理')
  await targetNode.getByRole('button', { name: '编辑 人工退款校验', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '人工退款校验', exact: true })
  const preview = detail.getByRole('region', { name: '用例内容预览', exact: true })
  await expect(preview).toContainText('人工条件：订单已支付')
  await expect(preview.getByRole('cell', { name: '输入退款金额', exact: true })).toBeVisible()
  await page.reload()
  await tree.getByRole('button', { name: '筛选模块 退款', exact: true }).click()
  await expect(page.getByRole('table', { name: '测试资产列表', exact: true }).locator('tbody tr').first()).toContainText('金额校验')
})

test('imported rich text renders readable case and frozen manual instructions without executing or loading document HTML', async ({ page, request, project }) => {
  const raw = '<p>已有<strong>支付订单</strong> &amp; 权限</p><script>window.__caseMarkupExecuted=true</script><img src="/__forbidden_image" onerror="window.__caseMarkupExecuted=true"><a href="javascript:window.__caseMarkupExecuted=true">仅作说明</a>'
  const asset = await create(request, project.id, 'FUNCTIONAL_CASE', '格式化用例', { precondition: raw })
  await create(request, project.id, 'FUNCTIONAL_STEP', '核对账单', { step: '<ol><li>打开账单</li><li>核对金额</li></ol>', expected: '<p>订单 &lt; 100 元</p>' }, asset.id)
  const loads: string[] = []
  page.on('request', request => { if (request.url().includes('/__forbidden_image')) loads.push(request.url()) })
  await page.goto('/cases')
  const table = page.getByRole('table', { name: '测试资产列表', exact: true })
  await expect(table).toContainText('已有支付订单 & 权限')
  await expect(table).not.toContainText('<p>')
  await table.getByRole('button', { name: '编辑 格式化用例', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '格式化用例', exact: true })
  const preview = detail.getByRole('region', { name: '用例内容预览', exact: true })
  await expect(preview).toContainText('已有支付订单 & 权限')
  await expect(preview.getByRole('cell', { name: '订单 < 100 元', exact: true })).toBeVisible()
  expect(await preview.locator('script,img,iframe').count()).toBe(0)
  expect(await preview.locator('a[href^="javascript:"]').count()).toBe(0)
  await detail.locator('.arco-drawer-close-btn').click()
  const submitted = await (await request.post(`/api/projects/${project.id}/runs`, { data: { assetId: asset.id, idempotencyKey: crypto.randomUUID() } })).json()
  await expect.poll(async () => (await (await request.get(`/api/jobs/${submitted.jobId}?projectId=${project.id}`)).json()).status).toBe('SUCCEEDED')
  await page.getByRole('button', { name: '执行记录', exact: true }).click()
  const runs = page.getByRole('dialog', { name: '执行记录', exact: true })
  await runs.getByRole('button', { name: '查看运行 格式化用例', exact: true }).click()
  const instructions = runs.getByRole('region', { name: '执行说明 格式化用例', exact: true })
  await expect(instructions).toContainText('已有支付订单 & 权限')
  await expect(instructions.getByRole('cell', { name: '订单 < 100 元', exact: true })).toBeVisible()
  await expect(instructions).not.toContainText('<p>')
  expect(loads).toEqual([])
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).__caseMarkupExecuted)).toBeUndefined()
  expect(await (await request.get(`/api/projects/${project.id}/assets/${asset.id}`)).json()).toEqual(asset)
})
