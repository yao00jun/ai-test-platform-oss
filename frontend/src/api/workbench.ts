import { http } from './client'

export interface QualityMetrics {
  schemaVersion: string; projectId: string; from: string; to: string; capturedAt: string
  runCount: number; itemCount: number; passRatePercent: number | null; failureCount: number
  itemStatuses: Record<string, number>; duration: { sampleCount: number; p95Ms: number | null }
}
export const workbenchApi = {
  quality: (projectId: string, signal?: AbortSignal) => http.request<QualityMetrics>(`/projects/${encodeURIComponent(projectId)}/quality-metrics`, { signal }),
}
