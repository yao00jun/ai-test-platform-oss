import { http, queryString } from './client'
export interface ImpactInput { baselineSnapshotId: string; idempotencyKey: string }
export interface ImpactSubmission { impactId: string; jobId: string }
export interface RegressionInput { assetIds: string[]; name: string; environmentId: string; idempotencyKey: string }
export interface RegressionSubmission { impactId: string; planId: string }
export interface ImpactCandidate { assetId: string; assetType: string; name: string; version: string; reasons: string[] }
export interface SourceImpact {
  id: string; projectId: string; sourceSnapshotId: string; baselineSnapshotId: string | null; jobId: string
  status: string; createdAt: string; completedAt: string | null; error: string | null; result?: Record<string, unknown>
}
const root = (project: string) => `/projects/${encodeURIComponent(project)}`
export const impactApi = {
  submit: (project: string, source: string, body: ImpactInput) => http.request<ImpactSubmission>(`${root(project)}/source-analyses/${encodeURIComponent(source)}/impact`, { method: 'POST', body }),
  list: (project: string, source: string, offset = 0, signal?: AbortSignal) => http.request<{ items: SourceImpact[]; total: number }>(`${root(project)}/source-analyses/${encodeURIComponent(source)}/impacts${queryString({ offset, limit: 25 })}`, { signal }),
  get: (project: string, id: string, signal?: AbortSignal) => http.request<SourceImpact>(`${root(project)}/source-impacts/${encodeURIComponent(id)}`, { signal }),
  plan: (project: string, impact: string, body: RegressionInput) => http.request<RegressionSubmission>(`${root(project)}/source-impacts/${encodeURIComponent(impact)}/regression-plan`, { method: 'POST', body }),
}
