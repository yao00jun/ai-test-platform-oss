import { http, queryString } from './client'
import type { Asset, AssetType, Page } from './types'

export const runnableTypes = new Set<AssetType>(['FUNCTIONAL_CASE', 'API_CASE', 'SCENARIO', 'UI_SCENARIO', 'SQL_VALIDATION', 'TEST_PLAN'])
export const runLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', PASSED: '通过', FAILED: '失败', ERROR: '执行错误', BLOCKED: '阻塞', SKIPPED: '跳过', CANCELLED: '已取消', INTERRUPTED: '已中断', MANUAL_PENDING: '待人工' }
export interface RunSummary { total: number; caseCount: number; dataRows: number; counts: Record<string, number> }
export interface RunRecord {
  id: string; projectId: string; assetId: string; jobId: string; name: string; status: string; summary: RunSummary
  createdAt: string; startedAt?: string; completedAt?: string
}
export interface RunAssertion { type: string; path?: string; operator?: string; expected?: unknown; actual?: unknown; passed: boolean; message?: string }
export interface RunStep {
  id: string; assetId: string; name: string; engine: string; status: string; position: number
  result: { status: string; durationMs: number; request: unknown; actual: unknown; assertions: RunAssertion[]; exports: unknown; artifactIds: string[]; error?: string }
}
export interface RunItem {
  id: string; assetId: string; name: string; assetType: AssetType; position: number; rowIndex: number | null
  variables: Record<string, unknown>; status: string; durationMs: number | null; error: string | null; notes: string | null; manualVersion: string; steps: RunStep[]
}
export interface RunDetail extends RunRecord { items: RunItem[]; snapshot: { rootId: string; environmentId: string; assets: Record<string, Asset> } }
export interface RunInput { assetId: string; environmentId?: string; datasetId?: string; idempotencyKey: string }
export interface RunSubmission { jobId: string; runId: string }
export interface ValidationResult { valid: boolean; errors: { assetId: string; name: string; field: string; variable?: string; message: string; rowIndex?: number }[]; availableVariables: string[]; checkedItems: number; errorsTruncated: boolean }
export interface DiagnosisOccurrence {
  id: string; runId: string; runItemId: string; bugId: string; jobId: string; status: string
  createdAt: string; evidence: unknown; diagnosis: unknown; modelStamp: string; promptVersion: string
}
const projectPath = (id: string) => `/projects/${encodeURIComponent(id)}`
const runPath = (projectId: string, id: string) => `${projectPath(projectId)}/runs/${encodeURIComponent(id)}`
export const runApi = {
  submit: (projectId: string, body: RunInput) => http.request<RunSubmission>(`${projectPath(projectId)}/runs`, { method: 'POST', body }),
  list: (projectId: string, offset = 0, signal?: AbortSignal) => http.request<Page<RunRecord>>(`${projectPath(projectId)}/runs${queryString({ offset, limit: 50 })}`, { signal }),
  get: (projectId: string, id: string, signal?: AbortSignal) => http.request<RunDetail>(runPath(projectId, id), { signal }),
  validate: (projectId: string, assetId: string, body: { environmentId?: string; datasetId?: string }, signal?: AbortSignal) => http.request<ValidationResult>(`${projectPath(projectId)}/assets/${encodeURIComponent(assetId)}/validate-execution`, { method: 'POST', body, signal }),
  manual: (projectId: string, runId: string, body: { itemId: string; baseVersion: string; status: string; notes: string }) => http.request<RunDetail>(`${runPath(projectId, runId)}/manual-result`, { method: 'POST', body }),
  report: (projectId: string, id: string, format: string, signal?: AbortSignal) => http.download(`${runPath(projectId, id)}/report${queryString({ format })}`, { signal }),
  file: (projectId: string, id: string, signal?: AbortSignal) => http.download(`${projectPath(projectId)}/files/${encodeURIComponent(id)}`, { signal }),
  diagnose: (projectId: string, id: string, idempotencyKey: string) => http.request<{ jobId: string; conversationId: string }>(`${runPath(projectId, id)}/diagnose`, { method: 'POST', body: { idempotencyKey } }),
  diagnoses: (projectId: string, id: string, signal?: AbortSignal) => http.request<{ items: DiagnosisOccurrence[] }>(`${runPath(projectId, id)}/diagnoses`, { signal }),
  occurrences: (projectId: string, id: string, signal?: AbortSignal) => http.request<{ items: DiagnosisOccurrence[]; occurrenceCount: number }>(`${projectPath(projectId)}/bugs/${encodeURIComponent(id)}/occurrences`, { signal }),
}
