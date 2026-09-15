import { http, queryString } from './client'
import type { Asset } from './types'

export interface ScheduleConfiguration { enabled: boolean; cronExpression: string; timezone: string; overlapPolicy: 'SKIP' | 'QUEUE'; misfirePolicy: 'SKIP' | 'FIRE_ONCE' }
export interface ScheduleOccurrence {
  id: string; status: string; reason: string; scheduledFor: string; misfireThrough: string | null
  planVersion: string; dispatchedPlanVersion: string | null; configuration: ScheduleConfiguration
  runId: string | null; jobId: string | null; runStatus: string | null; createdAt: string; dispatchedAt: string | null
}
export interface ScheduleState {
  planId: string; planVersion: string; configuration: ScheduleConfiguration; schedulerEnabled: boolean
  misfireGraceSeconds: number; maxQueued: number; queuedCount: number; nextFireAt: string | null; lastFireAt: string | null
  nextFireTimes: string[]; items: ScheduleOccurrence[]; total: number
}
const path = (projectId: string, planId: string) => `/projects/${encodeURIComponent(projectId)}/plans/${encodeURIComponent(planId)}/schedule`
export const scheduleApi = {
  state: (projectId: string, planId: string, offset = 0, signal?: AbortSignal) => http.request<ScheduleState>(`${path(projectId, planId)}${queryString({ offset, limit: 25 })}`, { signal }),
  save: (projectId: string, planId: string, body: { baseVersion: string } & Record<string, unknown>) => http.request<Asset>(path(projectId, planId), { method: 'PUT', body }),
  preview: (projectId: string, planId: string, body: { cronExpression: string; timezone: string }, signal?: AbortSignal) => http.request<{ timezone: string; from: string; nextFireTimes: string[] }>(`${path(projectId, planId)}/preview`, { method: 'POST', body, signal }),
}
export const occurrenceLabels: Record<string, string> = { WAITING: '等待前次运行', SUBMITTED: '已派发', SKIPPED_OVERLAP: '重叠已跳过', SKIPPED_MISFIRE: '错过已跳过', SKIPPED_CAPACITY: '队列已满', CANCELLED: '已取消', FAILED: '派发失败' }
