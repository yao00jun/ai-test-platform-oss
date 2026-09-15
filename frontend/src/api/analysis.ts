import { http, queryString } from './client'

export const sourceFieldNames = ['backendRepoPath', 'frontendRepoPath', 'sqlScriptPath', 'ddlText', 'backendRef', 'frontendRef', 'baselineRef'] as const
export const sourceSettingNames = ['backendRepoPath', 'frontendRepoPath', 'sqlScriptPath'] as const
export type SourceFields = Record<typeof sourceFieldNames[number], string>
export type SourceSettings = Pick<SourceFields, typeof sourceSettingNames[number]>
export interface SourceInput extends SourceFields { idempotencyKey: string }
export interface SourceSubmission { analysisId: string; jobId: string }
export interface SourceDiagnostic { severity: string; code: string; kind: string; path: string; line: number; message: string }
export interface SourceAnalysis {
  id: string; projectId: string; projectVersion: string; jobId: string; status: string
  fileCount: number; totalBytes: number; manifestHash: string | null; createdAt: string; completedAt: string | null; error: string | null
  configuration?: Partial<SourceFields>; diagnostics?: SourceDiagnostic[]; result?: Record<string, unknown>
}
export interface SourceFile { kind: string; path: string; sha256: string; bytes: number }
export interface SourceExcerpt extends Pick<SourceFile, 'kind' | 'path' | 'sha256'> { snapshotId: string; from: number; to: number; totalLines: number; content: string; evidenceLevel: string }
export const sourceStatusLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '正在分析', READY: '分析完整', PARTIAL: '部分分析 · 请查看诊断', FAILED: '分析失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
const path = (project: string) => `/projects/${encodeURIComponent(project)}/source-analyses`
export const analysisApi = {
  submit: (project: string, body: SourceInput) => http.request<SourceSubmission>(path(project), { method: 'POST', body }),
  list: (project: string, offset = 0, signal?: AbortSignal) => http.request<{ items: SourceAnalysis[]; total: number }>(path(project) + queryString({ offset, limit: 25 }), { signal }),
  get: (project: string, id: string, signal?: AbortSignal) => http.request<SourceAnalysis>(`${path(project)}/${encodeURIComponent(id)}`, { signal }),
  files: (project: string, id: string, offset = 0, signal?: AbortSignal) => http.request<{ items: SourceFile[]; total: number }>(`${path(project)}/${encodeURIComponent(id)}/files${queryString({ offset, limit: 100 })}`, { signal }),
  excerpt: (project: string, id: string, file: Pick<SourceFile, 'kind' | 'path'>, from = 1, signal?: AbortSignal) => http.request<SourceExcerpt>(`${path(project)}/${encodeURIComponent(id)}/file${queryString({ ...file, from, to: from + 99 })}`, { signal }),
}
