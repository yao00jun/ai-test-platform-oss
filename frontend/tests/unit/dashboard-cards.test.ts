import { describe, expect, it } from 'vitest'
import { parseDashboardCards, moveDashboardCard, updateDashboardCard } from '../../src/core/dashboard-cards'

describe('dashboard card edits', () => {
  it('upgrades legacy metrics without changing their stable ID, metric or order', () => {
    const result = parseDashboardCards([{ id: 'legacy', type: 'metric', title: '待处理', metric: 'openBugs' }])
    expect(result).toEqual([{ schemaVersion: 'aitest.dashboard-card/v1', id: 'legacy', type: 'metric', title: '待处理', metric: 'openBugs', size: 'small', visible: true, fields: ['value'] }])
  })
  it('changes one card and then reorders by ID without modifying its siblings or input', () => {
    const original = parseDashboardCards([{ id: 'first', type: 'metric', title: '用例', metric: 'cases' }, { id: 'second', type: 'metric', title: '缺陷', metric: 'openBugs' }])
    const updated = updateDashboardCard(original, 'second', { title: '已确认的缺陷', visible: false })
    const moved = moveDashboardCard(updated, 'second', 0)
    expect(moved.map(card => card.id)).toEqual(['second', 'first'])
    expect(moved[0]).toMatchObject({ title: '已确认的缺陷', visible: false, metric: 'openBugs' })
    expect(moved[1]).toEqual(original[0])
    expect(original[1]).toMatchObject({ title: '缺陷', visible: true })
    expect(() => updateDashboardCard(original, 'deleted', { title: 'wrong target' })).toThrow()
  })
  it('rejects duplicate identity, unknown display fields and fabricated values', () => {
    const card = { schemaVersion: 'aitest.dashboard-card/v1', id: 'quality', type: 'quality', title: '运行', fields: ['runCount'], visible: true, size: 'wide' }
    expect(() => parseDashboardCards([card, card])).toThrow()
    expect(() => parseDashboardCards([{ ...card, fields: ['imaginaryPassRate'] }])).toThrow()
    expect(() => parseDashboardCards([{ ...card, value: '100%' }])).toThrow()
    expect(() => parseDashboardCards([{ ...card, schemaVersion: 'aitest.dashboard-card/v2' }])).toThrow()
  })
})
