import { describe, expect, it } from 'vitest'
import { applyDatasetEdit, cellEditor, readDataset } from '../../src/core/dataset-grid'

function fixture() {
  return readDataset('["orderNo","qty","meta","optional"]', '[{"orderNo":"0012","qty":1,"meta":{"tags":["a"]},"optional":null},{"orderNo":"0090","qty":2,"meta":[false]}]')
}

describe('typed dataset editing', () => {
  it('updates only the selected cell, retaining string codes, nested values and missing versus null', () => {
    const original = fixture()
    expect(cellEditor(original, 0, 'orderNo')).toMatchObject({ kind: 'string', text: '0012' })
    expect(cellEditor(original, 0, 'optional').kind).toBe('null')
    expect(cellEditor(original, 1, 'optional').kind).toBe('missing')
    const updated = applyDatasetEdit(original, { type: 'cell', row: 0, column: 'qty', kind: 'number', text: '2.5' })
    expect(updated.rows).toEqual([
      { orderNo: '0012', qty: 2.5, meta: { tags: ['a'] }, optional: null },
      { orderNo: '0090', qty: 2, meta: [false] },
    ])
    expect(original.rows[0]?.qty).toBe(1)
    const removed = applyDatasetEdit(updated, { type: 'cell', row: 0, column: 'optional', kind: 'missing', text: '' })
    expect(Object.hasOwn(removed.rows[0]!, 'optional')).toBe(false)
    expect(updated.rows[0]?.optional).toBeNull()
  })

  it('rejects invalid typed values without changing the original dataset', () => {
    const original = fixture()
    for (const text of ['', ' ', 'NaN', 'Infinity', '0x10', '1e999', '1,2', '01']) {
      expect(() => applyDatasetEdit(original, { type: 'cell', row: 0, column: 'qty', kind: 'number', text })).toThrow(/有效数字/)
    }
    expect(() => applyDatasetEdit(original, { type: 'cell', row: 0, column: 'meta', kind: 'json', text: '{broken' })).toThrow(/JSON/)
    expect(() => applyDatasetEdit(original, { type: 'cell', row: 0, column: 'meta', kind: 'json', text: '{"total":1e999}' })).toThrow(/JSON/)
    expect(() => applyDatasetEdit(original, { type: 'cell', row: 0, column: 'qty', kind: 'boolean', text: 'yes' })).toThrow(/true.*false/)
    expect(original.rows[0]).toEqual({ orderNo: '0012', qty: 1, meta: { tags: ['a'] }, optional: null })
  })

  it('renames and moves columns without filling missing values or changing row order', () => {
    const original = fixture()
    const added = applyDatasetEdit(original, { type: 'add-column', name: 'region' })
    const renamed = applyDatasetEdit(added, { type: 'rename-column', column: 'qty', name: 'amount' })
    const moved = applyDatasetEdit(renamed, { type: 'move-column', column: 'amount', to: 0 })
    expect(moved.columns).toEqual(['amount', 'orderNo', 'meta', 'optional', 'region'])
    expect(moved.rows).toEqual([
      { orderNo: '0012', amount: 1, meta: { tags: ['a'] }, optional: null },
      { orderNo: '0090', amount: 2, meta: [false] },
    ])
    expect(original.columns).toEqual(['orderNo', 'qty', 'meta', 'optional'])
    const deleted = applyDatasetEdit(moved, { type: 'remove-column', column: 'optional' })
    expect(Object.hasOwn(deleted.rows[0]!, 'optional')).toBe(false)
    expect(() => applyDatasetEdit(original, { type: 'rename-column', column: 'qty', name: 'orderNo' })).toThrow(/唯一/)
    expect(() => applyDatasetEdit(original, { type: 'add-column', name: '   ' })).toThrow(/非空/)
    expect(original.rows[0]?.qty).toBe(1)
  })

  it('keeps row operations immutable and rejects stale indexes', () => {
    const original = fixture()
    const reordered = applyDatasetEdit(original, { type: 'move-row', row: 1, to: 0 })
    expect(reordered.rows.map(row => row.orderNo)).toEqual(['0090', '0012'])
    const appended = applyDatasetEdit(reordered, { type: 'add-row' })
    expect(appended.rows).toHaveLength(3)
    expect(appended.rows[2]).toEqual({})
    const removed = applyDatasetEdit(appended, { type: 'remove-row', row: 0 })
    expect(removed.rows).toEqual([{ orderNo: '0012', qty: 1, meta: { tags: ['a'] }, optional: null }, {}])
    expect(original.rows.map(row => row.orderNo)).toEqual(['0012', '0090'])
    expect(() => applyDatasetEdit(original, { type: 'move-row', row: 2, to: 0 })).toThrow(/数据行/)
    expect(() => applyDatasetEdit(original, { type: 'cell', row: 0, column: 'absent', kind: 'string', text: 'x' })).toThrow(/数据列/)
  })

  it('treats JSON property names as data and rejects malformed table shapes', () => {
    const special = readDataset('["__proto__","constructor"]', '[{"__proto__":{"safe":true},"constructor":"001"}]')
    const updated = applyDatasetEdit(special, { type: 'rename-column', column: 'constructor', name: 'toString' })
    expect(Object.keys(updated.rows[0]!)).toEqual(['__proto__', 'toString'])
    expect(JSON.stringify(updated.rows)).toBe('[{"__proto__":{"safe":true},"toString":"001"}]')
    for (const [columns, rows] of [['{}', '[]'], ['["a","a"]', '[]'], ['["a"]', '[null]'], ['["a"]', '[{"b":1}]']]) {
      expect(() => readDataset(columns!, rows!)).toThrow()
    }
    expect(() => readDataset(JSON.stringify(Array.from({ length: 201 }, (_, index) => `c${index}`)), '[]')).toThrow(/200/)
    expect(() => readDataset('["a"]', '[{"a":1e999}]')).toThrow(/JSON/)
  })
})
