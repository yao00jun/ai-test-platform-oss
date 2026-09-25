import { http, queryString } from './client'
import type { AcceptedJob, Asset, AssetType, Conversation, Job, ModelSettings } from './types'

export const aiApi = {
  settings: (signal?: AbortSignal) => http.request<ModelSettings>('/settings/model', { signal }),
  saveSettings: (body: { baseUrl: string; modelName: string; apiKey?: string; temperature?: number; timeoutSeconds?: number; requestsPerMinute?: number; trustSelfSigned?: boolean }) =>
    http.request<ModelSettings>('/settings/model', { method: 'PUT', body }),
  testSettings: () => http.request<{ ok: boolean; message: string }>('/settings/model/test', { method: 'POST' }),
  listModels: (body: { baseUrl: string; apiKey?: string; trustSelfSigned?: boolean }, signal?: AbortSignal) => http.request<{ models: string[] }>('/settings/model/models', { method: 'POST', body, signal }),
  refine: (asset: Asset, feedback: string, conversationId?: string, idempotencyKey: string = crypto.randomUUID(), targetFields?: string[]) => http.request<AcceptedJob>('/ai/refine-item', {
    method: 'POST', body: { projectId: asset.projectId, targetType: asset.type, targetId: asset.id, baseVersion: asset.version, conversationId, feedback, applyMode: 'REPLACE_ON_SUCCESS', idempotencyKey, targetFields },
  }),
  generate: (body: { projectId: string; type: AssetType; parentId?: string; runId?: string; sourceSnapshotId?: string; instruction: string; conversationId?: string; idempotencyKey?: string }) => http.request<AcceptedJob>('/ai/generate', {
    method: 'POST', body: { ...body, idempotencyKey: body.idempotencyKey ?? crypto.randomUUID() },
  }),
  conversation: (projectId: string, id: string, signal?: AbortSignal) => http.request<Conversation>(`/ai/conversations/${encodeURIComponent(id)}${queryString({ projectId })}`, { signal }),
  job: (projectId: string, id: string, signal?: AbortSignal) => http.request<Job>(`/jobs/${encodeURIComponent(id)}${queryString({ projectId })}`, { signal }),
  cancel: (projectId: string, id: string) => http.request<Job>(`/jobs/${encodeURIComponent(id)}/cancel`, { method: 'POST', body: { projectId } }),
}
