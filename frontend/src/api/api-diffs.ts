import { http, queryString } from './client'
import type { AcceptedJob, Asset } from './types'

export interface ApiDiffLink { fromId: string; toId: string; field: string }
export interface ApiDiffItem {
  id: string; kind: 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED' | 'AMBIGUOUS'
  match: 'EXPLICIT' | 'OPERATION_ID' | 'METHOD_PATH' | 'NONE' | 'ABSENT'
  importKey: string | null; definitionId: string | null; before: Asset | null
  candidate: { key: string; name: string; parentId: string | null; data: Record<string, unknown> } | null
  matchCandidates: string[]; fields: { path: string; kind: string; before: unknown; after: unknown }[]
  affectedAssetIds: string[]; links: ApiDiffLink[]; originalAffectedAssetIds: string[]; originalLinks: ApiDiffLink[]
  status: 'PENDING' | 'ACCEPTED'; appliedVersion: string | null; current: Asset | null
}
export interface ApiDiff {
  id: string; projectId: string; importId: string; filename: string
  status: 'READY' | 'UNCHANGED' | 'PARTIAL' | 'APPLIED'; createdAt: string; items: ApiDiffItem[]
}
export interface ApiDiffSummary extends Pick<ApiDiff, 'id' | 'importId' | 'filename' | 'status' | 'createdAt'> { changeCount: number }
export interface ApiDiffApply { itemIds: string[]; idempotencyKey: string }
const path = (project: string) => `/projects/${encodeURIComponent(project)}/api-diffs`
export const apiDiffApi = {
  preview: (project: string, importId: string, definitionMappings: Record<string, string>, signal?: AbortSignal) => http.request<ApiDiff>(`${path(project)}/preview`, { method: 'POST', body: { importId, definitionMappings }, signal }),
  list: (project: string, offset = 0, signal?: AbortSignal) => http.request<{ items: ApiDiffSummary[]; total: number }>(`${path(project)}${queryString({ offset, limit: 30 })}`, { signal }),
  get: (project: string, id: string, signal?: AbortSignal) => http.request<ApiDiff>(`${path(project)}/${encodeURIComponent(id)}`, { signal }),
  apply: (project: string, id: string, input: ApiDiffApply, signal?: AbortSignal) => http.request<{ diffId: string; assets: Asset[]; removedIds: string[]; acceptedItemIds: string[] }>(`${path(project)}/${encodeURIComponent(id)}/apply`, { method: 'POST', body: input, signal }),
  heal: (project: string, id: string, body: { instruction: string; conversationId?: string; idempotencyKey: string }) => http.request<AcceptedJob>(`${path(project)}/${encodeURIComponent(id)}/heal`, { method: 'POST', body }),
}
