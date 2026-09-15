import { expect, it } from 'vitest'
import type { CatalogType } from '../../src/api/types'
import { compileUiStepDsl } from '../../src/core/ui-step-dsl'

const definition: CatalogType = {
  type: 'UI_STEP', label: 'UI 步骤', childTypes: [], formats: ['json'], fields: [
    { key: 'action', label: '动作', kind: 'select', required: true, defaultValue: 'click', options: ['click', 'fill', 'upload'] },
    { key: 'selector', label: '定位器', kind: 'text', required: false },
    { key: 'value', label: '输入值', kind: 'text', required: false },
    { key: 'timeoutMs', label: '超时', kind: 'number', required: false, defaultValue: 15000 },
    { key: 'exactMatch', label: '精确匹配', kind: 'boolean', required: false, defaultValue: true },
    { key: 'fileIds', label: '附件', kind: 'json', required: false, defaultValue: [] },
  ],
}

it('applies only supplied DSL fields and preserves omitted current settings and metadata', () => {
  const original = { action: 'fill', selector: '#username', value: 'before', timeoutMs: 8000, exactMatch: false, fileIds: [], source: { document: 'observed-page' } }
  expect(compileUiStepDsl(definition, '{"value":"0012"}', original)).toEqual({ ...original, value: '0012' })
  expect(original.value).toBe('before')
})

it('rejects target identity fields, unknown actions and implicit type coercion', () => {
  for (const raw of ['{"id":"another-step"}', '{"parentId":"another-parent"}', '{"version":"8"}', '{"action":"eval"}', '{"timeoutMs":"1500"}', '{"exactMatch":"false"}', '{"fileIds":{}}', '[]', 'null']) {
    expect(() => compileUiStepDsl(definition, raw, { action: 'click' })).toThrow()
  }
  expect(() => compileUiStepDsl(definition, '{"timeoutMs":1e999}', {})).toThrow(/数字/)
})

it('keeps explicit empty values and typed arrays when clearing step settings', () => {
  expect(compileUiStepDsl(definition, '{"value":"","exactMatch":false,"fileIds":[]}', { action: 'upload', value: 'old', fileIds: ['old-file'] })).toEqual({ action: 'upload', value: '', exactMatch: false, fileIds: [] })
})

it('keeps captured evidence and source binding outside the step DSL even when the catalog lists them', () => {
  const withEvidence: CatalogType = { ...definition, fields: [...definition.fields,
    { key: 'generationEvidence', label: '生成依据', kind: 'json', required: false, defaultValue: {} },
    { key: 'sourceSnapshotId', label: '固定源码', kind: 'text', required: false },
  ] }
  const original = { selector: '#old', sourceSnapshotId: 'fixed-source', generationEvidence: { runtime: { page: 'captured' } } }
  expect(() => compileUiStepDsl(withEvidence, '{"sourceSnapshotId":"other-source"}', original)).toThrow()
  expect(() => compileUiStepDsl(withEvidence, '{"generationEvidence":{"runtime":"invented"}}', original)).toThrow()
  expect(compileUiStepDsl(withEvidence, '{"selector":"#new"}', original)).toEqual({ ...original, selector: '#new' })
})
