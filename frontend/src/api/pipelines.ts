import { http, queryString } from './client'
import type { Asset } from './types'

export const stageNames = { S1: '需求分析', S2: '功能用例', S3: '接口场景链', S4: 'SQL 校验', S5: 'Playwright UI', S6: '执行与诊断' } as const
export type PipelineStage = keyof typeof stageNames
export const pipelineLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '运行中', COMPLETED: '已完成', COMPLETED_WITH_GAPS: '已完成 · 有待补充项', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断', BLOCKED: '待补充', SKIPPED: '已跳过', WAITING_RUN: '等待测试执行', WAITING_DIAGNOSIS: '等待失败诊断' }
export const activePipelineStates = new Set(['QUEUED', 'RUNNING'])
export interface PipelineOptions { environmentId: string; databaseSourceIds: string[]; uiEvidenceIds: string[]; execute: boolean; sourceSnapshotId?: string }
export interface PipelineInput extends PipelineOptions { projectId: string; requirementIds: string[]; apiDefinitionIds: string[]; idempotencyKey: string }
export interface PipelineResume extends PipelineOptions { projectId: string; stage: PipelineStage; apiDefinitionIds: string[]; idempotencyKey: string }
export interface PipelineAccepted { pipelineId: string; jobId: string; activeJobId?: string; conversationId: string; status: string }
export interface PipelineSummary { id: string; status: string; currentStage: PipelineStage; progress: number; createdAt: string; updatedAt: string }
export interface PipelineAttempt { id: string; stage: PipelineStage; status: string; attempt: number; jobId: string; input: unknown; output: Record<string, unknown>; error?: string; startedAt?: string; completedAt?: string }
export interface PipelineExecution extends Record<string, unknown> { execution?: string; planId?: string; runId?: string; runIds?: string[]; runSummary?: { total: number; caseCount: number; dataRows: number; counts: Record<string, number> }; reason?: string; requiresExecutionResume?: boolean; pendingAssetIds?: string[] }
export interface Pipeline extends PipelineSummary { projectId: string; jobId: string; conversationId: string; revision: string; config: PipelineOptions & { requirementIds: string[]; apiDefinitionIds: string[]; request: PipelineInput }; assetIds: string[]; runId?: string; error?: string; steps: PipelineAttempt[]; attempts: PipelineAttempt[]; execution: PipelineExecution }
const path = (id: string) => `/ai/pipelines/${encodeURIComponent(id)}`
export const pipelineApi = {
  submit: (body: PipelineInput) => http.request<PipelineAccepted>('/ai/pipelines', { method: 'POST', body }),
  list: (projectId: string, signal?: AbortSignal) => http.request<PipelineSummary[]>(`/ai/pipelines${queryString({ projectId })}`, { signal }),
  get: (projectId: string, id: string, signal?: AbortSignal) => http.request<Pipeline>(`${path(id)}${queryString({ projectId })}`, { signal }),
  resume: (id: string, body: PipelineResume) => http.request<PipelineAccepted>(`${path(id)}/resume`, { method: 'POST', body }),
  cancel: (projectId: string, id: string) => http.request<Pipeline>(`${path(id)}/cancel`, { method: 'POST', body: { projectId } }),
  readDocument: (projectId: string, body: { path?: string; text?: string; name?: string; idempotencyKey: string }) => http.request<Asset>(`/projects/${encodeURIComponent(projectId)}/documents/read`, { method: 'POST', body }),
  uploadDocument: (projectId: string, file: File, idempotencyKey: string) => { const form = new FormData(); form.append('file', file); form.append('idempotencyKey', idempotencyKey); return http.request<Asset>(`/projects/${encodeURIComponent(projectId)}/documents`, { method: 'POST', body: form }) },
}
