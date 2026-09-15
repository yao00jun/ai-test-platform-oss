import { http, queryString } from './client'
import type { AcceptedJob, Asset, AssetType } from './types'

export interface ChangeItem {
  id: string
  operation: 'ADD' | 'MODIFY' | 'DELETE'
  targetType: AssetType
  targetId: string | null
  parentId: string | null
  localKey: string | null
  baseVersion: string | null
  before: Asset | null
  after: { name: string | null; data: Record<string, unknown> }
  validation: { valid: boolean; [key: string]: unknown }
}
export interface ChangeSet { id: string; status: 'DRAFT' | 'APPLIED' | 'REJECTED'; items: ChangeItem[] }
export interface FeedbackRequest {
  projectId: string
  apiDiffId?: string
  pipelineId?: string
  conversationId?: string
  sourceSnapshotId?: string
  assetIds?: string[]
  feedback: string
  idempotencyKey: string
}
export const feedbackApi = {
  submit: (body: FeedbackRequest) => http.request<AcceptedJob>('/ai/feedback', { method: 'POST', body }),
  changes: (projectId: string, id: string, signal?: AbortSignal) => http.request<ChangeSet>(`/ai/change-sets/${encodeURIComponent(id)}${queryString({ projectId })}`, { signal }),
  apply: (projectId: string, id: string, itemIds: string[], signal?: AbortSignal) => http.request<{ status: 'APPLIED'; assets: Asset[] }>(`/ai/change-sets/${encodeURIComponent(id)}/apply`, { method: 'POST', body: { projectId, itemIds }, signal }),
  reject: (projectId: string, id: string, signal?: AbortSignal) => http.request<void>(`/ai/change-sets/${encodeURIComponent(id)}/reject`, { method: 'POST', body: { projectId }, signal }),
}
