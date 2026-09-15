import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { completeImpactSubmission, completeRegressionSubmission, newImpactDraft, readImpactDraft, writeImpactDraft } from '../../src/core/source-impact-state'

const storage = new Map<string, string>()
const source = 'a'.repeat(32), impact = 'b'.repeat(32), plan = 'c'.repeat(32), asset = 'd'.repeat(32)
beforeEach(() => {
  storage.clear()
  vi.stubGlobal('sessionStorage', { getItem: (name: string) => storage.get(name) ?? null, setItem: (name: string, value: string) => storage.set(name, value) })
})
afterEach(() => vi.unstubAllGlobals())

it('does not let late responses overwrite a replacement request or another source draft', () => {
  const draft = newImpactDraft('project-a', source); draft.analysisRequest = { baselineSnapshotId: '', idempotencyKey: 'newer-request' }
  writeImpactDraft(draft)
  expect(completeImpactSubmission('project-a', source, 'older-request', { impactId: impact, jobId: 'job' })).toBeUndefined()
  expect(readImpactDraft('project-a', source)?.analysisRequest?.idempotencyKey).toBe('newer-request')
  expect(readImpactDraft('project-b', source)).toBeUndefined()
  expect(completeImpactSubmission('project-a', source, 'newer-request', { impactId: impact, jobId: 'job' })?.selectedImpactId).toBe(impact)
})
it('restores exact selected order and only consumes the matching plan acknowledgement', () => {
  const draft = newImpactDraft('project-plan', source)
  draft.selectedImpactId = impact
  draft.planRequest = { impactId: impact, body: { assetIds: [asset, plan], name: 'My plan', environmentId: '', idempotencyKey: 'plan-submit' } }
  writeImpactDraft(draft)
  expect(readImpactDraft('project-plan', source)?.planRequest).toEqual(draft.planRequest)
  expect(completeRegressionSubmission('project-plan', source, 'plan-submit', { impactId: asset, planId: plan })).toBeUndefined()
  expect(completeRegressionSubmission('project-plan', source, 'plan-submit', { impactId: impact, planId: plan })?.planId).toBe(plan)
  expect(readImpactDraft('project-plan', source)?.planRequest).toBeUndefined()
})
it('uses volatile memory when storage quota rejects writes without fabricating completion', () => {
  vi.stubGlobal('sessionStorage', { getItem: () => null, setItem: () => { throw new Error('Quota full') } })
  const draft = newImpactDraft('quota-project', source); draft.analysisRequest = { baselineSnapshotId: asset, idempotencyKey: 'quota-request' }
  expect(writeImpactDraft(draft)).toBe(false)
  expect(readImpactDraft('quota-project', source)?.analysisRequest).toEqual(draft.analysisRequest)
})
