import { isRecord } from '../api/client'
import type { PipelineInput, PipelineOptions } from '../api/pipelines'
import { sourceFieldNames, type SourceFields, type SourceInput, type SourceSubmission } from '../api/analysis'

export interface PipelineSourcePreparation {
  mode: 'none' | 'existing' | 'prepare'
  fields: SourceFields
  snapshotId?: string
  request?: SourceInput
  submission?: SourceSubmission
}
export const emptyPipelineSource = (): PipelineSourcePreparation => ({ mode: 'none', fields: Object.fromEntries(sourceFieldNames.map(name => [name, ''])) as SourceFields })

export interface PipelinePreparation {
  id: string
  requirementHash?: string
  requirementKey?: string
  requirementIds?: string[]
  apiHash?: string
  importId?: string
  apiDefinitionIds?: string[]
  options?: PipelineOptions
  request?: PipelineInput
  source?: PipelineSourcePreparation
}
const key = (project: string) => `ai-test-platform:pipeline-preparation:${project}`
const memory = new Map<string, PipelinePreparation>()
const volatile = new Set<string>()
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const identifiers = (value: unknown): value is string[] => Array.isArray(value) && value.every(identifier)
function pipelineOptions(value: unknown): value is PipelineOptions {
  return isRecord(value) && typeof value.environmentId === 'string' && identifiers(value.databaseSourceIds) && identifiers(value.uiEvidenceIds) && typeof value.execute === 'boolean'
    && (value.sourceSnapshotId === undefined || typeof value.sourceSnapshotId === 'string' && value.sourceSnapshotId.length <= 128)
}
function sourcePreparation(value: unknown): value is PipelineSourcePreparation {
  if (!isRecord(value) || !['none', 'existing', 'prepare'].includes(String(value.mode)) || !sourceFields(value.fields)) return false
  if (value.snapshotId !== undefined && !identifier(value.snapshotId)) return false
  if (value.submission !== undefined && (!isRecord(value.submission) || !identifier(value.submission.analysisId) || !identifier(value.submission.jobId))) return false
  if (value.request !== undefined && (!isRecord(value.request) || !identifier(value.request.idempotencyKey) || value.request.idempotencyKey.length > 120 || !sourceFields(value.request))) return false
  return true
}
function sourceFields(value: unknown): value is SourceFields {
  return isRecord(value) && sourceFieldNames.every(field => typeof value[field] === 'string' && value[field].length <= (field === 'ddlText' ? 2_000_000 : 2048))
}
function pipelineInput(value: unknown, project: string): value is PipelineInput {
  return isRecord(value) && value.projectId === project && identifier(value.idempotencyKey)
    && identifiers(value.requirementIds) && value.requirementIds.length > 0 && identifiers(value.apiDefinitionIds)
    && pipelineOptions(value)
}
function preparation(value: unknown, project: string): PipelinePreparation | undefined {
  if (!isRecord(value) || !identifier(value.id)) return
  for (const field of ['requirementHash', 'requirementKey', 'apiHash', 'importId']) if (value[field] !== undefined && !identifier(value[field])) return
  for (const field of ['requirementIds', 'apiDefinitionIds']) if (value[field] !== undefined && !identifiers(value[field])) return
  if (value.options !== undefined && !pipelineOptions(value.options)) return
  if (value.request !== undefined && !pipelineInput(value.request, project)) return
  if (value.source !== undefined && !sourcePreparation(value.source)) return
  return {
    id: value.id,
    requirementHash: value.requirementHash as string | undefined,
    requirementKey: value.requirementKey as string | undefined,
    requirementIds: value.requirementIds as string[] | undefined,
    apiHash: value.apiHash as string | undefined,
    importId: value.importId as string | undefined,
    apiDefinitionIds: value.apiDefinitionIds as string[] | undefined,
    source: value.source,
    options: value.options === undefined ? undefined : {
      environmentId: value.options.environmentId, databaseSourceIds: value.options.databaseSourceIds,
      uiEvidenceIds: value.options.uiEvidenceIds, execute: value.options.execute,
      ...(value.options.sourceSnapshotId === undefined ? {} : { sourceSnapshotId: value.options.sourceSnapshotId }),
    },
    request: value.request === undefined ? undefined : {
      projectId: value.request.projectId, idempotencyKey: value.request.idempotencyKey,
      requirementIds: value.request.requirementIds, apiDefinitionIds: value.request.apiDefinitionIds,
      environmentId: value.request.environmentId, databaseSourceIds: value.request.databaseSourceIds,
      uiEvidenceIds: value.request.uiEvidenceIds, execute: value.request.execute,
      ...(value.request.sourceSnapshotId === undefined ? {} : { sourceSnapshotId: value.request.sourceSnapshotId }),
    },
  }
}
export function readPreparation(project: string): PipelinePreparation | undefined {
  if (volatile.has(project)) return memory.get(project)
  let raw: string | null
  try { raw = localStorage.getItem(key(project)) }
  catch { volatile.add(project); return memory.get(project) }
  try {
    const saved = preparation(JSON.parse(raw ?? 'null'), project)
    if (saved) { memory.set(project, saved); return saved }
  } catch { /* Ignore corrupted recovery data rather than submitting a partial request. */ }
  // An absent key may mean another tab confirmed the submission. Do not revive it.
  memory.delete(project)
  return undefined
}
export function beginPreparation(project: string, fresh = false): PipelinePreparation {
  const saved = fresh ? undefined : readPreparation(project)
  if (saved) return saved
  const draft = { id: crypto.randomUUID() }; write(project, draft); return draft
}
export function updatePreparation(project: string, id: string, patch: Partial<PipelinePreparation>): PipelinePreparation | undefined {
  const saved = readPreparation(project)
  if (!saved || saved.id !== id) return undefined
  const updated = { ...saved, ...patch }; write(project, updated); return updated
}
export function completePreparation(project: string, id: string) {
  if (readPreparation(project)?.id !== id) return
  memory.delete(project)
  try { localStorage.removeItem(key(project)) } catch { /* Optional browser persistence. */ }
}
function write(project: string, draft: PipelinePreparation) {
  memory.set(project, draft)
  try { localStorage.setItem(key(project), JSON.stringify(draft)); volatile.delete(project) }
  catch { volatile.add(project) }
}
export async function inputHash(value: string | Blob): Promise<string> {
  const bytes = typeof value === 'string' ? new TextEncoder().encode(value) : await value.arrayBuffer()
  const hash = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
}
