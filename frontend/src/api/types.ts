export type AssetType =
  | 'PROJECT' | 'ENVIRONMENT' | 'AUTH_CONFIG' | 'DATABASE_SOURCE' | 'MODULE' | 'REQUIREMENT'
  | 'FUNCTIONAL_CASE' | 'FUNCTIONAL_STEP' | 'API_DEFINITION' | 'API_CASE' | 'SCENARIO'
  | 'SCENARIO_STEP' | 'SQL_VALIDATION' | 'DATASET' | 'UI_SCENARIO' | 'UI_STEP'
  | 'TEST_PLAN' | 'PLAN_ITEM' | 'BUG' | 'DASHBOARD' | 'QUALITY_BRIEF' | 'WEBHOOK'

export interface Asset {
  id: string
  projectId: string
  type: AssetType
  parentId: string | null
  name: string
  version: string
  position: number
  source: 'MANUAL' | 'AI' | 'IMPORT'
  confirmed: boolean
  createdAt: string
  updatedAt: string
  data: Record<string, unknown>
}

export interface Page<T> { items: T[]; total: number }
export type FieldKind = 'text' | 'textarea' | 'number' | 'boolean' | 'select' | 'json' | 'sql' | 'code' | 'password'
export interface CatalogField {
  key: string
  label: string
  kind: FieldKind
  required: boolean
  options?: (string | { label: string; value: string })[]
  defaultValue?: unknown
}
export interface CatalogType {
  type: AssetType
  label: string
  fields: CatalogField[]
  childTypes: AssetType[]
  formats: string[]
}
export interface AssetPatch {
  baseVersion: string
  name?: string
  confirmed?: boolean
  data?: Record<string, unknown>
}
export interface AssetDraft { name: string; data: Record<string, unknown>; confirmed?: boolean }
export interface Revision {
  id: string
  assetId: string
  version: string
  operation: string
  source: string
  createdAt: string
  snapshot: Asset
}
export interface ModelSettings {
  baseUrl: string
  modelName: string
  hasApiKey: boolean
  temperature: number
  timeoutSeconds: number
  requestsPerMinute: number
  trustSelfSigned: boolean
}
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export interface Job {
  id: string
  projectId: string
  kind: string
  status: JobStatus
  progress: number
  message: string
  result: unknown
  error: unknown
  createdAt: string
  updatedAt: string
}
export interface AcceptedJob { jobId: string; conversationId: string }
export interface ConversationMessage {
  id: string
  role: string
  content: string
  status: string
  createdAt: string
  baseVersion?: string
  appliedVersion?: string
  candidate?: unknown
  validation?: unknown
}
export interface Conversation { id: string; scope: string; targetId: string; messages: ConversationMessage[] }
export interface GenerationScope { projectId: string; type: AssetType; parentId?: string; runId?: string; sourceSnapshotId?: string; label: string }
export interface ProjectSummary {
  counts: Record<string, number>
  openBugCount?: number
  runSummary: Record<string, number>
  recentRuns: Record<string, unknown>[]
  recentBugs: Asset[]
}
