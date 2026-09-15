import { isRecord } from '../api/client'

export interface CodeDiagnosisDraft { root_cause: string; affected_code_path: string; suggested_fix: string; is_regression: string; confidence: string }
export function codeDiagnosisDraft(value: unknown): CodeDiagnosisDraft {
  const data = isRecord(value) ? value : {}
  return {
    root_cause: typeof data.root_cause === 'string' ? data.root_cause : '',
    affected_code_path: typeof data.affected_code_path === 'string' ? data.affected_code_path : '',
    suggested_fix: typeof data.suggested_fix === 'string' ? data.suggested_fix : '',
    is_regression: typeof data.is_regression === 'boolean' ? String(data.is_regression) : '',
    confidence: typeof data.confidence === 'number' && Number.isFinite(data.confidence) ? String(data.confidence) : '',
  }
}
export function compileCodeDiagnosis(draft: CodeDiagnosisDraft): Record<string, unknown> {
  if (Object.values(draft).every(value => !value.trim())) return {}
  const confidence = draft.confidence.trim() ? Number(draft.confidence) : null
  if (confidence !== null && (!Number.isFinite(confidence) || confidence < 0 || confidence > 1)) throw new Error('置信度应为 0 到 1 之间的数值，留空表示未知。')
  if (!['', 'true', 'false'].includes(draft.is_regression)) throw new Error('请选择有效的退化推测。')
  return { formatVersion: 'aitest.code-rca/v1', root_cause: draft.root_cause, affected_code_path: draft.affected_code_path.trim(), suggested_fix: draft.suggested_fix, is_regression: draft.is_regression === '' ? null : draft.is_regression === 'true', confidence }
}
export function rebaseCodeDiagnosisDraft(baseline: CodeDiagnosisDraft, draft: CodeDiagnosisDraft, latest: CodeDiagnosisDraft): CodeDiagnosisDraft {
  const result = { ...latest }
  for (const key of Object.keys(draft) as (keyof CodeDiagnosisDraft)[]) if (draft[key] !== baseline[key]) result[key] = draft[key]
  return result
}
