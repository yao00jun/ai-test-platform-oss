export type DashboardCardType = 'metric' | 'quality' | 'assets' | 'bugs' | 'runs' | 'evalops'
export interface DashboardCard {
  schemaVersion: 'aitest.dashboard-card/v1'; id: string; type: DashboardCardType; title: string
  size: 'small' | 'wide' | 'full'; visible: boolean; fields: string[]; metric?: 'cases' | 'openBugs'; limit?: number
}
interface CardDefinition { label: string; fields: { key: string; label: string }[] }
const fields = (pairs: [string, string][]) => pairs.map(([key, label]) => ({ key, label }))
export const cardDefinitions: Record<DashboardCardType, CardDefinition> = {
  metric: { label: '单项指标', fields: fields([['value', '指标数值']]) },
  quality: { label: '运行质量', fields: fields([['runCount', '运行次数'], ['itemCount', '运行项数'], ['passRatePercent', '运行项通过率'], ['failureCount', '失败与阻塞项'], ['pendingCount', '待完成项'], ['p95Ms', 'P95 耗时（毫秒）']]) },
  assets: { label: '测试资产', fields: fields([['FUNCTIONAL_CASE', '功能用例数'], ['API_CASE', '接口用例数'], ['API_DEFINITION', '接口定义数'], ['SCENARIO', '场景数'], ['SQL_VALIDATION', 'SQL 校验数'], ['UI_SCENARIO', 'UI 场景数'], ['TEST_PLAN', '测试计划数'], ['BUG', '缺陷数'], ['QUALITY_BRIEF', '简报数'], ['DATASET', '数据集数'], ['REQUIREMENT', '需求数']]) },
  bugs: { label: '最近缺陷', fields: fields([['name', '名称'], ['severity', '严重度'], ['status', '状态'], ['updatedAt', '更新时间']]) },
  runs: { label: '最近运行', fields: fields([['name', '名称'], ['status', '状态'], ['createdAt', '创建时间']]) },
  evalops: { label: '模型效能', fields: fields([['invocations', '模型调用数'], ['totalTokens', '已报告 Token'], ['validRatePercent', '生成有效率'], ['passRatePercent', 'AI 资产执行通过率'], ['correctRatePercent', '人工评价 RCA 正确率'], ['confirmedRegressionBugs', '人工确认退化缺陷数'], ['estimatedCost', '已计价调用估算']]) },
}
export const cardSizes = [{ value: 'small', label: '紧凑 · 三分之一行' }, { value: 'wide', label: '标准 · 半行' }, { value: 'full', label: '宽幅 · 整行' }]
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }

export function parseDashboardCards(value: unknown): DashboardCard[] {
  const cards: unknown = typeof value === 'string' ? JSON.parse(value) : value
  if (!Array.isArray(cards) || cards.length > 50) throw new Error('看板需要卡片数组，最多 50 张卡片。')
  const ids = new Set<string>()
  return cards.map((card: unknown) => {
    if (!object(card)) throw new Error('每张卡片必须是配置对象。')
    const { id, title, type } = card
    if (typeof id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(id) || ids.has(id)) throw new Error('卡片 ID 必须合法且在布局内唯一。')
    ids.add(id)
    if (typeof title !== 'string' || !title.trim() || title.length > 120) throw new Error('卡片标题需要 1–120 个字符。')
    if (typeof type !== 'string' || !Object.hasOwn(cardDefinitions, type)) throw new Error('卡片类型无效。')
    const kind = type as DashboardCardType
    if (Object.hasOwn(card, 'schemaVersion') && card.schemaVersion !== 'aitest.dashboard-card/v1') throw new Error('不支持此卡片版本。')
    const allowed = new Set(['schemaVersion', 'id', 'type', 'title', 'size', 'visible', 'fields', ...(kind === 'metric' ? ['metric'] : []), ...(['bugs', 'runs'].includes(kind) ? ['limit'] : [])])
    if (Object.keys(card).some(key => !allowed.has(key))) throw new Error('卡片包含未知配置；统计数值不能写入布局。')
    const size = Object.hasOwn(card, 'size') ? card.size : kind === 'metric' ? 'small' : 'wide'
    if (size !== 'small' && size !== 'wide' && size !== 'full') throw new Error('卡片尺寸无效。')
    const visible = Object.hasOwn(card, 'visible') ? card.visible : true
    if (typeof visible !== 'boolean') throw new Error('卡片可见性必须为布尔值。')
    const allowedFields = cardDefinitions[kind].fields.map(field => field.key)
    const selected: unknown = Object.hasOwn(card, 'fields') ? card.fields : allowedFields
    if (!Array.isArray(selected) || !selected.length || selected.some(key => typeof key !== 'string' || !allowedFields.includes(key)) || new Set(selected).size !== selected.length) throw new Error('显示字段必须非空、唯一且属于此卡片类型。')
    const result: DashboardCard = { schemaVersion: 'aitest.dashboard-card/v1', id, type: kind, title, size, visible, fields: [...selected] as string[] }
    if (kind === 'metric') {
      const metric = Object.hasOwn(card, 'metric') ? card.metric : 'cases'
      if (metric !== 'cases' && metric !== 'openBugs') throw new Error('指标类型无效。')
      result.metric = metric
    }
    if (kind === 'bugs' || kind === 'runs') {
      const limit = Object.hasOwn(card, 'limit') ? card.limit : 5
      if (typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1 || limit > 10) throw new Error('卡片记录数为 1–10 的整数。')
      result.limit = limit
    }
    return result
  })
}
export function updateDashboardCard(cards: DashboardCard[], id: string, patch: Partial<Omit<DashboardCard, 'id' | 'schemaVersion'>>): DashboardCard[] {
  if (!cards.some(card => card.id === id)) throw new Error('目标卡片已不存在。')
  return cards.map(card => card.id === id ? { ...card, ...patch, id: card.id, schemaVersion: card.schemaVersion } : card)
}
export function moveDashboardCard(cards: DashboardCard[], id: string, to: number): DashboardCard[] {
  const from = cards.findIndex(card => card.id === id)
  if (from < 0) throw new Error('目标卡片已不存在。')
  if (!Number.isInteger(to) || to < 0 || to >= cards.length || from === to) return cards
  const result = [...cards]
  const [card] = result.splice(from, 1)
  result.splice(to, 0, card!)
  return result
}
export function createDashboardCard(type: DashboardCardType, id: string = crypto.randomUUID()): DashboardCard {
  return parseDashboardCards([{ id, type, title: cardDefinitions[type].label }])[0]!
}
export function defaultDashboardCards(): DashboardCard[] {
  return parseDashboardCards([
    { id: 'quality', type: 'quality', title: '最近 24 小时质量', fields: ['runCount', 'itemCount', 'passRatePercent', 'failureCount'] },
    { id: 'assets', type: 'assets', title: '当前测试资产', fields: ['FUNCTIONAL_CASE', 'API_CASE', 'UI_SCENARIO', 'TEST_PLAN'] },
    { id: 'runs', type: 'runs', title: '最近运行' }, { id: 'bugs', type: 'bugs', title: '最近更新的缺陷' },
  ])
}
