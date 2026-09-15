import { http, queryString } from './client'

export interface FailureSourceEvidence {
  formatVersion: 'aitest.failure-source-evidence/v1'
  bindings: { sourceSnapshotId: string; manifestHash: string; status: string }[]
  locations: { id: string; sourceSnapshotId: string; path: string; sha256: string; className: string; method: string; line: number; from: number; to: number; content: string }[]
  diffs: { sourceSnapshotId: string; path: string; diff: string }[]
  diagnostics: { code: string; message: string }[]
  complete: boolean
}
export type RcaVerdict = 'CORRECT' | 'PARTIAL' | 'INCORRECT' | 'UNREVIEWED'
export type RegressionVerdict = 'CONFIRMED' | 'NOT_REGRESSION' | 'UNREVIEWED'
export interface RcaEvaluation {
  id: string; bugId: string; assetVersion: string; diagnosisHash: string; verdict: RcaVerdict; regression: RegressionVerdict
  note: string; actor: string; source: string; createdAt: string
}
export interface RcaEvaluations { current: RcaEvaluation | null; items: RcaEvaluation[]; total: number; diagnosisHash: string }
export const verdictLabels: Record<RcaVerdict, string> = { CORRECT: '正确', PARTIAL: '部分正确', INCORRECT: '错误', UNREVIEWED: '未评价' }
export const regressionLabels: Record<RegressionVerdict, string> = { CONFIRMED: '已确认退化', NOT_REGRESSION: '非退化', UNREVIEWED: '未确认' }
const path = (project: string, bug: string) => `/projects/${encodeURIComponent(project)}/bugs/${encodeURIComponent(bug)}`
export const bugApi = {
  codeEvidence: (project: string, bug: string, signal?: AbortSignal) => http.request<FailureSourceEvidence>(`${path(project, bug)}/code-evidence`, { signal }),
  evaluations: (project: string, bug: string, offset = 0, signal?: AbortSignal) => http.request<RcaEvaluations>(`${path(project, bug)}/rca-evaluations${queryString({ offset, limit: 25 })}`, { signal }),
  evaluate: (project: string, bug: string, body: { baseVersion: string; verdict: RcaVerdict; regression: RegressionVerdict; note: string; idempotencyKey: string }) => http.request<RcaEvaluation>(`${path(project, bug)}/rca-evaluations`, { method: 'POST', body }),
}
