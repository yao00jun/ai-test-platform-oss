import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beginPreparation, completePreparation, readPreparation, updatePreparation } from '../../src/core/pipeline-preparation'
import type { PipelineInput } from '../../src/api/pipelines'

const storage = new Map<string, string>()
const key = (project: string) => `ai-test-platform:pipeline-preparation:${project}`
const request = (projectId: string): PipelineInput => ({ projectId, requirementIds: ['requirement-1'], apiDefinitionIds: ['api-1'], environmentId: '', databaseSourceIds: [], uiEvidenceIds: [], execute: false, idempotencyKey: 'submission-1' })

beforeEach(() => {
  storage.clear()
  vi.stubGlobal('localStorage', { getItem: (name: string) => storage.get(name) ?? null, setItem: (name: string, value: string) => storage.set(name, value), removeItem: (name: string) => storage.delete(name) })
})
afterEach(() => vi.unstubAllGlobals())

describe('pipeline submission recovery', () => {
  it.each([
    { request: request('another-project') },
    { request: { ...request('invalid'), requirementIds: 'requirement-1' } },
    { request: { ...request('invalid'), execute: 'true' } },
    { requirementIds: [17] },
  ])('rejects malformed or cross-project recovery state %#', value => {
    storage.set(key('invalid'), JSON.stringify({ id: 'draft-invalid', ...value }))
    expect(readPreparation('invalid')).toBeUndefined()
  })

  it('honors completion in another tab instead of resurrecting a cached pending request', () => {
    const draft = beginPreparation('completed-in-other-tab')
    updatePreparation('completed-in-other-tab', draft.id, { request: request('completed-in-other-tab') })
    storage.delete(key('completed-in-other-tab'))
    expect(readPreparation('completed-in-other-tab')).toBeUndefined()
  })

  it('restores the exact request and rejects a late response from an explicitly replaced preparation', () => {
    const first = beginPreparation('replace')
    updatePreparation('replace', first.id, { requirementIds: ['requirement-1'], request: request('replace') })
    expect(readPreparation('replace')?.request).toEqual(request('replace'))
    const second = beginPreparation('replace', true)
    expect(updatePreparation('replace', first.id, { requirementIds: ['late-requirement'] })).toBeUndefined()
    completePreparation('replace', first.id)
    expect(readPreparation('replace')?.id).toBe(second.id)
    completePreparation('replace', second.id)
    expect(readPreparation('replace')).toBeUndefined()
  })

  it('retains the submission identity when browser storage is unavailable', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('storage disabled') }, setItem: () => { throw new Error('storage disabled') }, removeItem: () => { throw new Error('storage disabled') } })
    const draft = beginPreparation('memory-only')
    updatePreparation('memory-only', draft.id, { request: request('memory-only') })
    expect(beginPreparation('memory-only').request).toEqual(request('memory-only'))
    completePreparation('memory-only', draft.id)
    expect(readPreparation('memory-only')).toBeUndefined()
  })

  it('keeps newly prepared IDs when storage remains readable but its quota rejects writes', () => {
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => { throw new Error('quota exceeded') }, removeItem: () => {} })
    const draft = beginPreparation('quota')
    updatePreparation('quota', draft.id, { requirementIds: ['already-created'] })
    expect(beginPreparation('quota').requirementIds).toEqual(['already-created'])
  })

  it('retains the exact source analysis identity and fixed source when recovering a lost acknowledgement', () => {
    const source = {
      mode: 'prepare',
      fields: { backendRepoPath: 'E:\\业务代码', frontendRepoPath: '', sqlScriptPath: '', ddlText: 'CREATE TABLE orders(id INT);', backendRef: 'main', frontendRef: '', baselineRef: '' },
      request: { backendRepoPath: 'E:\\业务代码', frontendRepoPath: '', sqlScriptPath: '', ddlText: 'CREATE TABLE orders(id INT);', backendRef: 'main', frontendRef: '', baselineRef: '', idempotencyKey: 'capture-same-input' },
      submission: { analysisId: 'source-original', jobId: 'analysis-job' },
    }
    const input = { ...request('source-recovery'), sourceSnapshotId: 'source-original' }
    storage.set(key('source-recovery'), JSON.stringify({ id: 'preparing-source', source, request: input }))
    expect(readPreparation('source-recovery')).toMatchObject({ id: 'preparing-source', source, request: input })
    const replacement = beginPreparation('source-recovery', true)
    expect(updatePreparation('source-recovery', 'preparing-source', { requirementIds: ['late-original'] })).toBeUndefined()
    expect(readPreparation('source-recovery')?.id).toBe(replacement.id)
  })

  it.each([
    { request: { ...request('broken-source'), sourceSnapshotId: { id: 'not-an-id' } } },
    { source: { mode: 'prepare', fields: { backendRepoPath: 'E:\\code' }, request: { idempotencyKey: 'truncated' } } },
    { source: { mode: 'existing', snapshotId: 12 } },
  ])('rejects corrupted source recovery instead of silently submitting without evidence %#', value => {
    storage.set(key('broken-source'), JSON.stringify({ id: 'broken-source', ...value }))
    expect(readPreparation('broken-source')).toBeUndefined()
  })
})
