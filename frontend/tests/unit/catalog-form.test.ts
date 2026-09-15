import { describe, expect, it } from 'vitest'
import { compileFields, initialFieldValues } from '../../src/core/catalog-form'
import type { CatalogType } from '../../src/api/types'

const definition: CatalogType = {
  type: 'API_DEFINITION', label: '接口定义', childTypes: ['API_CASE'], formats: ['json'],
  fields: [
    { key: 'method', label: '方法', kind: 'select', required: true, options: ['GET', 'POST'], defaultValue: 'GET' },
    { key: 'headers', label: '请求头', kind: 'json', required: false },
    { key: 'timeout', label: '超时', kind: 'number', required: true },
  ],
}

describe('catalog-driven input validation', () => {
  it('rejects malformed JSON and non-finite numbers before submission', () => {
    const result = compileFields(definition, { method: 'GET', headers: '{broken', timeout: Number.NaN })
    expect(Object.keys(result.errors).sort()).toEqual(['headers', 'timeout'])
    expect(result.data.headers).toBeUndefined()
  })

  it('converts JSON text to structured fields while retaining unknown server data', () => {
    expect(compileFields(definition, { method: 'POST', headers: '{"Accept":"application/json"}', timeout: 30 }, { metadata: { managed: true } })).toEqual({
      data: { method: 'POST', headers: { Accept: 'application/json' }, timeout: 30, metadata: { managed: true } }, errors: {},
    })
  })

  it('keeps a deliberately empty existing value instead of replacing it with a catalog default', () => {
    expect(initialFieldValues(definition, { method: '', headers: { x: 1 }, timeout: 0 })).toEqual({
      method: '', headers: '{\n  "x": 1\n}', timeout: 0,
    })
  })
})
