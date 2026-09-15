import type { CatalogType } from '../api/types'

export function initialFieldValues(definition: CatalogType, data: Record<string, unknown> = {}): Record<string, unknown> {
  return Object.fromEntries(definition.fields.map((field) => {
    const value = Object.hasOwn(data, field.key) ? data[field.key] : field.defaultValue
    return [field.key, field.kind === 'json' ? (value == null ? '' : JSON.stringify(value, null, 2)) : value ?? (field.kind === 'boolean' ? false : '')]
  }))
}

export function compileFields(definition: CatalogType, values: Record<string, unknown>, original: Record<string, unknown> = {}): { data: Record<string, unknown>; errors: Record<string, string> } {
  const data = { ...original }
  const errors: Record<string, string> = {}
  for (const field of definition.fields) {
    const value = values[field.key]
    const empty = value === undefined || value === null || (typeof value === 'string' && !value.trim())
    if (field.required && empty) { errors[field.key] = `请填写${field.label}`; continue }
    if (field.kind === 'json') {
      if (empty) { if (Object.hasOwn(original, field.key)) data[field.key] = null; continue }
      try { data[field.key] = JSON.parse(String(value)) } catch { errors[field.key] = 'JSON 格式无效，请检查引号、逗号和括号。' }
    } else if (field.kind === 'number') {
      if (empty && !field.required) continue
      const number = Number(value)
      if (!Number.isFinite(number)) errors[field.key] = '请输入有效数字'
      else data[field.key] = number
    } else if (field.kind === 'boolean') {
      data[field.key] = value === true
    } else if (field.kind === 'password' && empty && Object.hasOwn(original, field.key)) {
      // A blank secret keeps the server-provided/redacted value out of mutation payloads.
      continue
    } else {
      data[field.key] = typeof value === 'string' ? value : value ?? ''
    }
  }
  return { data, errors }
}
