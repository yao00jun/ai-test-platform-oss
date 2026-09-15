export interface ConversationReference {
  id: string
  projectId: string
  targetKey: string
  jobId: string
  updatedAt: string
  submissionOrder?: number
  assetIds?: string[]
  sourceSnapshotId?: string
}
const key = 'ai-test-platform:conversation-index'
const orderKey = 'ai-test-platform:conversation-order'
let lastOrder = 0
const order = (entry: ConversationReference) => entry.submissionOrder ?? Date.parse(entry.updatedAt)

/** Capture intent order before POST, so an older acknowledgement cannot become the newest round. */
export function reserveConversationOrder(): number {
  try { lastOrder = Math.max(lastOrder, Number(localStorage.getItem(orderKey)) || 0) } catch { /* In-memory ordering still works without storage. */ }
  lastOrder = Math.max(Date.now(), lastOrder + 1)
  try { localStorage.setItem(orderKey, String(lastOrder)) } catch { /* Discovery is optional. */ }
  return lastOrder
}

/** This local index stores server IDs only; conversation content is always retrieved from the API. */
export function readConversations(projectId: string, targetKey: string): ConversationReference[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    if (!Array.isArray(raw)) return []
    return raw.filter((entry): entry is ConversationReference => typeof entry === 'object' && entry !== null && typeof entry.id === 'string' && typeof entry.jobId === 'string' && typeof entry.updatedAt === 'string' && entry.projectId === projectId && entry.targetKey === targetKey)
      .sort((a, b) => order(b) - order(a))
  } catch { return [] }
}
export function rememberConversation(entry: ConversationReference): void {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    const existing = Array.isArray(raw) ? raw.find(item => item && typeof item === 'object' && item.id === entry.id) as ConversationReference | undefined : undefined
    if (existing && order(existing) > order(entry)) return
    const entries = Array.isArray(raw) ? raw.filter((item) => item && typeof item === 'object' && item.id !== entry.id) : []
    localStorage.setItem(key, JSON.stringify([entry, ...entries].slice(0, 100)))
  } catch { /* Disabled storage affects discovery only; active conversations remain usable. */ }
}

export function rememberConversationScope(projectId: string, targetKey: string, id: string, jobId: string, assetIds: string[]): void {
  const reference = readConversations(projectId, targetKey).find(entry => entry.id === id && entry.jobId === jobId)
  if (reference) rememberConversation({ ...reference, assetIds: [...assetIds] })
}
