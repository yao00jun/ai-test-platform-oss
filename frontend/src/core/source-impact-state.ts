import { isRecord } from '../api/client'
import type { ImpactInput, ImpactSubmission, RegressionInput, RegressionSubmission } from '../api/source-impact'

export interface ImpactDraft {
  projectId: string; sourceId: string; baselineId: string; selectedImpactId: string; selectedIds: string[]
  planName: string; environmentId: string; planId: string
  analysisRequest?: ImpactInput; planRequest?: { impactId: string; body: RegressionInput }
}
const memory = new Map<string, ImpactDraft>(), volatile = new Set<string>()
const key = (project: string, source: string) => `ai-test-platform:source-impact:${project}:${source}`
const identity = (value: unknown): value is string => typeof value === 'string' && (value === '' || /^[a-f0-9]{32}$/.test(value))
const requestKey = (value: unknown): value is string => typeof value === 'string' && !!value && value.length <= 120
const ids = (value: unknown): value is string[] => Array.isArray(value) && value.length <= 10000 && value.every(item => !!item && identity(item)) && new Set(value).size === value.length
export function newImpactDraft(projectId: string, sourceId: string): ImpactDraft {
  return { projectId, sourceId, baselineId: '', selectedImpactId: '', selectedIds: [], planName: '', environmentId: '', planId: '' }
}
function parse(value: unknown, project: string, source: string): ImpactDraft | undefined {
  if (!isRecord(value) || value.projectId !== project || value.sourceId !== source || !identity(value.baselineId) || !identity(value.selectedImpactId) || !identity(value.environmentId) || !identity(value.planId) || !ids(value.selectedIds) || typeof value.planName !== 'string' || value.planName.length > 200) return
  if (value.analysisRequest !== undefined && (!isRecord(value.analysisRequest) || !identity(value.analysisRequest.baselineSnapshotId) || !requestKey(value.analysisRequest.idempotencyKey))) return
  if (value.planRequest !== undefined) {
    if (!isRecord(value.planRequest) || !value.planRequest.impactId || !identity(value.planRequest.impactId) || !isRecord(value.planRequest.body)) return
    const body = value.planRequest.body
    if (!ids(body.assetIds) || !body.assetIds.length || typeof body.name !== 'string' || body.name.length > 200 || !identity(body.environmentId) || !requestKey(body.idempotencyKey)) return
  }
  return value as unknown as ImpactDraft
}
export function readImpactDraft(project: string, source: string): ImpactDraft | undefined {
  const name = key(project, source)
  if (volatile.has(name)) return memory.get(name)
  try {
    const saved = parse(JSON.parse(sessionStorage.getItem(name) ?? 'null'), project, source)
    if (saved) { memory.set(name, saved); return saved }
    memory.delete(name)
  } catch { return memory.get(name) }
}
export function writeImpactDraft(state: ImpactDraft): boolean {
  const name = key(state.projectId, state.sourceId), encoded = JSON.stringify(state)
  memory.set(name, JSON.parse(encoded) as ImpactDraft)
  try { sessionStorage.setItem(name, encoded); volatile.delete(name); return true } catch { volatile.add(name); return false }
}
export function completeImpactSubmission(project: string, source: string, request: string, result: ImpactSubmission) {
  const saved = readImpactDraft(project, source)
  if (saved?.analysisRequest?.idempotencyKey !== request) return
  saved.analysisRequest = undefined; saved.selectedImpactId = result.impactId; saved.selectedIds = []; saved.planId = ''
  writeImpactDraft(saved); return saved
}
export function completeRegressionSubmission(project: string, source: string, request: string, result: RegressionSubmission) {
  const saved = readImpactDraft(project, source)
  if (saved?.planRequest?.body.idempotencyKey !== request || saved.planRequest.impactId !== result.impactId) return
  saved.planRequest = undefined; saved.planId = result.planId
  writeImpactDraft(saved); return saved
}
