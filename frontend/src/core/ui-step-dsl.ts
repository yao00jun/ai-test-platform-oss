import type { CatalogType } from '../api/types'

/** Step DSL is a patch to one step's allowed configuration, never an identity or sibling edit. */
export function compileUiStepDsl(definition: CatalogType, text: string, current: Record<string, unknown>): Record<string, unknown> {
  let parsed: unknown
  try { parsed = JSON.parse(text) } catch { throw new Error('步骤 DSL 必须是有效的 JSON 对象。') }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('步骤 DSL 必须是 JSON 对象。')
  const fields = new Map(definition.fields.filter(field => !['sourceSnapshotId', 'generationEvidence'].includes(field.key)).map(field => [field.key, field]))
  for (const [key, value] of Object.entries(parsed)) {
    const field = fields.get(key)
    if (!field) throw new Error(`步骤 DSL 不允许修改字段 ${key}。`)
    if (field.kind === 'number') {
      if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${field.label}必须为有效数字。`)
    } else if (field.kind === 'boolean') {
      if (typeof value !== 'boolean') throw new Error(`${field.label}必须为布尔值 true 或 false。`)
    } else if (field.kind === 'json') {
      if (Array.isArray(field.defaultValue) && !Array.isArray(value)) throw new Error(`${field.label}必须为数组。`)
      if (field.defaultValue && typeof field.defaultValue === 'object' && !Array.isArray(field.defaultValue) && (!value || typeof value !== 'object' || Array.isArray(value))) throw new Error(`${field.label}必须为对象。`)
    } else if (typeof value !== 'string') throw new Error(`${field.label}必须为文本。`)
    if (field.kind === 'select' && !(field.options ?? []).some(option => (typeof option === 'string' ? option : option.value) === value)) throw new Error(`${field.label}不在允许的选项中。`)
  }
  return { ...current, ...parsed }
}
