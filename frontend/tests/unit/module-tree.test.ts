import { expect, it } from 'vitest'
import type { Asset } from '../../src/api/types'
import { modulePath, moduleRows } from '../../src/core/module-tree'

function module(id: string, name: string, parentId: string | null, position: number): Asset {
  return { id, name, parentId, position, projectId: 'project', type: 'MODULE', version: '1', source: 'MANUAL', confirmed: false, createdAt: '', updatedAt: '', data: {} }
}

it('shows nested modules in sibling order and resolves full paths independently of input order', () => {
  const modules = [module('refund', '退款', 'order', 2), module('payment', '支付', 'order', 1), module('order', '交易', null, 1), module('user', '用户', null, 2)]
  expect(moduleRows(modules).map(row => [row.asset.id, row.depth, row.path])).toEqual([
    ['order', 0, '交易'], ['payment', 1, '交易 / 支付'], ['refund', 1, '交易 / 退款'], ['user', 0, '用户'],
  ])
  expect(modulePath(modules, 'refund')).toBe('交易 / 退款')
  expect(modulePath(modules, null)).toBe('未分类')
})

it('keeps orphaned and cyclic module records visible once without traversing forever', () => {
  const modules = [module('orphan', '未载入父级', 'missing', 0), module('a', '甲', 'b', 1), module('b', '乙', 'a', 2)]
  const rows = moduleRows(modules)
  expect(rows.map(row => row.asset.id)).toEqual(['orphan', 'a', 'b'])
  expect(modulePath(modules, 'a')).toBe('乙 / 甲')
  expect(modulePath(modules, 'missing')).toBe('所属模块未加载')
  expect(modules[1]?.parentId).toBe('b')
})
