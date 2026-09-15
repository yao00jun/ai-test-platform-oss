import { describe, expect, it } from 'vitest'
import { buildAssetPatch } from '../../src/core/asset-patch'
import type { Asset } from '../../src/api/types'

const original: Asset = {
  id: 'case-1', projectId: 'project-1', type: 'FUNCTIONAL_CASE', parentId: null,
  name: '支付测试', version: '9007199254740993', position: 0, source: 'MANUAL',
  confirmed: false, createdAt: '2026-09-14T00:00:00Z', updatedAt: '2026-09-14T00:00:00Z',
  data: { priority: 'P1', description: '旧内容', metadata: { a: 1, b: 2 }, steps: [{ id: 'child-1' }] },
}

describe('asset mutation payloads', () => {
  it('patches only edited catalog fields and preserves independent child arrays', () => {
    expect(buildAssetPatch(original, {
      name: '支付测试', data: { ...original.data, description: '新增边界', steps: [] },
    }, ['priority', 'description', 'metadata'])).toEqual({
      baseVersion: '9007199254740993', data: { description: '新增边界' },
    })
  })

  it('does not erase unseen fields or send unchanged object values', () => {
    expect(buildAssetPatch(original, {
      name: '支付测试', data: { metadata: { b: 2, a: 1 } },
    }, ['metadata'])).toBeNull()
  })

  it('supports independent confirmation and trimmed inline names without resending data', () => {
    expect(buildAssetPatch(original, { name: ' 支付异常测试 ', confirmed: true })).toEqual({
      baseVersion: '9007199254740993', name: '支付异常测试', confirmed: true,
    })
  })
})
