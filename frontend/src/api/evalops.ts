import { http, queryString } from './client'

export interface ModelPrice {
  modelName: string; version: string; enabled: boolean; currency: string
  inputPerMillion: string | number | null; outputPerMillion: string | number | null
}
export interface GenerationMetrics {
  attempts: number; finished: number; valid: number; invalid: number; blocked: number
  active: number; cancelled: number; interrupted: number; validRatePercent: number | null
}
export interface UsageMetrics {
  invocations: number; succeeded: number; failed: number; cancelled: number; interrupted: number; active: number
  httpAttempts: number; usageReported: number; usageMissing: number; promptTokens: number | null
  completionTokens: number | null; totalTokens: number | null; pricedInvocations: number
  averageDurationMs: number | null; durationSamples: number
  costByCurrency: { currency: string; invocations: number; estimatedCost: number | string }[]
}
export interface EvalOps {
  schemaVersion: string; projectId: string; from: string; to: string; capturedAt: string
  usage: UsageMetrics; generation: GenerationMetrics
  execution: { aiItems: number; executed: number; passed: number; failed: number; errors: number; blocked: number; pending: number; skipped: number; interrupted: number; passRatePercent: number | null }
  rca: { evaluatedVersions: number; judgedVersions: number; clearedVersions: number; correct: number; partial: number; incorrect: number; correctRatePercent: number | null; confirmedRegressionBugs: number; regressionInterceptionRatePercent: null }
  byModel: (UsageMetrics & { modelName: string; modelVersion: string; generation: GenerationMetrics })[]
}
export interface ModelInvocation {
  id: string; projectId: string; jobId: string; modelName: string; modelVersion: string; responseModel: string | null
  templateName: string; templateVersion: string; purpose: string; status: string; errorCode: string | null
  startedAt: string; completedAt: string | null; durationMs: number | null; httpAttempts: number; usageReported: boolean
  promptTokens: number | null; completionTokens: number | null; totalTokens: number | null
  pricingVersion: string | null; currency: string | null; estimatedCost: number | string | null
}
export interface MetricsWindow { from?: string; to?: string }
export const evalopsApi = {
  summary: (project: string, window: MetricsWindow, signal?: AbortSignal) => http.request<EvalOps>(`/projects/${encodeURIComponent(project)}/evalops${queryString({ ...window })}`, { signal }),
  invocations: (project: string, window: MetricsWindow, offset: number, modelName?: string, modelVersion?: string, signal?: AbortSignal) => http.request<{ items: ModelInvocation[]; total: number }>(`/projects/${encodeURIComponent(project)}/evalops/invocations${queryString({ ...window, offset, limit: 10, modelName, modelVersion })}`, { signal }),
  price: (modelName: string, signal?: AbortSignal) => http.request<ModelPrice>(`/settings/model/pricing${queryString({ modelName })}`, { signal }),
  savePrice: (body: Omit<ModelPrice, 'version'> & { baseVersion: string }) => http.request<ModelPrice>('/settings/model/pricing', { method: 'PUT', body }),
}
