import { http } from './client'

export interface MorningBriefForm { enabled: boolean; time: string; timezone: string; instruction: string; maxRetries: number }
export interface MorningBriefSchedule extends MorningBriefForm { projectId: string; version: string; nextFireAt: string | null; schedulerEnabled: boolean }
export interface MorningBriefAttempt {
  number: number; jobId: string; status: string; error: string | null; origin: string
  createdAt: string; nextRetryAt: string | null; diagnosticCode: string | null
}
export interface MorningBriefOccurrence {
  id: string; localDate: string; timezone: string; scheduledFor: string; missedFrom: string | null
  windowFrom: string; windowTo: string; status: string; attemptCount: number; assetId: string | null
  metrics: Record<string, unknown>; instruction: string; maxRetries: number; automaticRetries: number
  createdAt: string; attempts: MorningBriefAttempt[]
}
const base = (project: string) => `/projects/${encodeURIComponent(project)}/morning-brief`
export const morningBriefApi = {
  settings: (project: string, signal?: AbortSignal) => http.request<MorningBriefSchedule>(`${base(project)}/schedule`, { signal }),
  save: (project: string, baseVersion: string, form: MorningBriefForm) => http.request<MorningBriefSchedule>(`${base(project)}/schedule`, { method: 'PUT', body: { baseVersion, ...form } }),
  history: (project: string, offset: number, signal?: AbortSignal) => http.request<{ items: MorningBriefOccurrence[]; total: number }>(`${base(project)}/history?offset=${offset}&limit=5`, { signal }),
  retry: (project: string, occurrence: string, idempotencyKey: string) => http.request<{ occurrenceId: string; jobId: string }>(`${base(project)}/occurrences/${encodeURIComponent(occurrence)}/retry`, { method: 'POST', body: { idempotencyKey } }),
}
