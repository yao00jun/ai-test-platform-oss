import { http } from './client'
import type { RunSummary } from './runs'

export interface NotificationAttempt {
  id: string; number: number; jobId: string; origin: string; configVersion: string
  outcome: string; jobStatus: string; diagnosticCode: string | null; httpStatus: number | null
  createdAt: string; startedAt: string | null; completedAt: string | null
}
export interface NotificationDelivery {
  id: string; runId: string; status: string; attemptCount: number; maxRetries: number; automaticRetries: number
  nextRetryAt: string | null; createdAt: string; updatedAt: string; attempts: NotificationAttempt[]
  report: { planName: string; projectName: string; status: string; completedAt: string; summary: RunSummary }
}
export interface NotificationRetry { baseVersion: string; idempotencyKey: string; acknowledgeUncertain: boolean }
const base = (project: string, webhook: string) => `/projects/${encodeURIComponent(project)}/webhooks/${encodeURIComponent(webhook)}/deliveries`
export const notificationApi = {
  history: (project: string, webhook: string, offset: number, signal?: AbortSignal) => http.request<{ items: NotificationDelivery[]; total: number; senderEnabled: boolean }>(`${base(project, webhook)}?offset=${offset}&limit=10`, { signal }),
  retry: (project: string, webhook: string, id: string, body: NotificationRetry) => http.request<{ deliveryId: string; attemptId: string; jobId: string }>(`${base(project, webhook)}/${encodeURIComponent(id)}/retry`, { method: 'POST', body }),
}
