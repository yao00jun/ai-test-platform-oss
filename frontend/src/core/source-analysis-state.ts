import { isRecord } from '../api/client'
import { sourceFieldNames, sourceSettingNames, type SourceFields, type SourceInput, type SourceSettings, type SourceSubmission } from '../api/analysis'
import type { Asset } from '../api/types'

export interface SourceDraftState {
  projectId: string; baseVersion: string; original: SourceSettings; draft: SourceFields
  request?: SourceInput; selectedId?: string
}
const key = (project: string) => `ai-test-platform:source-analysis:${project}`
const memory = new Map<string, SourceDraftState>(), volatile = new Set<string>()
export function sourceSettings(project: Asset): SourceSettings {
  return { backendRepoPath: String(project.data.backendRepoPath ?? ''), frontendRepoPath: String(project.data.frontendRepoPath ?? ''), sqlScriptPath: String(project.data.sqlScriptPath ?? '') }
}
export function createSourceDraft(project: Asset): SourceDraftState {
  const original = sourceSettings(project)
  return { projectId: project.id, baseVersion: project.version, original, draft: { ...original, ddlText: '', backendRef: '', frontendRef: '', baselineRef: '' } }
}
function fields(value: unknown): SourceFields | undefined {
  if (!isRecord(value) || !sourceFieldNames.every(name => typeof value[name] === 'string' && value[name].length <= (name === 'ddlText' ? 2_000_000 : 2048))) return
  return Object.fromEntries(sourceFieldNames.map(name => [name, value[name]])) as SourceFields
}
function parse(value: unknown, project: string): SourceDraftState | undefined {
  if (!isRecord(value) || value.projectId !== project || typeof value.baseVersion !== 'string' || !/^\d+$/.test(value.baseVersion) || !isRecord(value.original)) return
  const original = value.original
  if (!sourceSettingNames.every(name => typeof original[name] === 'string')) return
  const draft = fields(value.draft); if (!draft) return
  let request: SourceInput | undefined
  if (value.request !== undefined) {
    if (!isRecord(value.request) || typeof value.request.idempotencyKey !== 'string' || !value.request.idempotencyKey || value.request.idempotencyKey.length > 120) return
    const input = fields(value.request); if (!input) return
    request = { ...input, idempotencyKey: value.request.idempotencyKey }
  }
  if (value.selectedId !== undefined && (typeof value.selectedId !== 'string' || !/^[a-f0-9]{32}$/.test(value.selectedId))) return
  return { projectId: project, baseVersion: value.baseVersion, original: Object.fromEntries(sourceSettingNames.map(name => [name, original[name]])) as SourceSettings, draft, request, selectedId: value.selectedId as string | undefined }
}
export function readSourceDraft(project: string): SourceDraftState | undefined {
  if (volatile.has(project)) return memory.get(project)
  try {
    const saved = parse(JSON.parse(sessionStorage.getItem(key(project)) ?? 'null'), project)
    if (saved) { memory.set(project, saved); return saved }
    memory.delete(project)
  } catch { return memory.get(project) }
}
export function writeSourceDraft(state: SourceDraftState): boolean {
  const encoded = JSON.stringify(state)
  memory.set(state.projectId, JSON.parse(encoded) as SourceDraftState)
  try { sessionStorage.setItem(key(state.projectId), encoded); volatile.delete(state.projectId); return true }
  catch { volatile.add(state.projectId); return false }
}
/** A late POST can finish in its original project without mutating whichever project is now on screen. */
export function completeSourceSubmission(project: string, requestKey: string, result: SourceSubmission) {
  const saved = readSourceDraft(project)
  if (saved?.request?.idempotencyKey !== requestKey) return
  saved.request = undefined; saved.selectedId = result.analysisId
  writeSourceDraft(saved)
  return saved
}
