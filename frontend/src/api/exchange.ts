import { http, queryString } from './client'
import type { AssetType } from './types'

export interface ExchangeCapability { type: AssetType; importFormats: string[]; exportFormats: string[] }
export interface TemplateVariant { format: string; importFormat: string; type: AssetType; filename: string; instructions: string }
export interface TemplateFamily { family: string; label: string; version: string; variants: TemplateVariant[] }
export interface ImportIssue { source: string; row: number; field: string; message: string }
export interface ImportNode { key: string; type: AssetType; parentKey?: string; name: string; position: number; data: Record<string, unknown>; references: Record<string, string> }
export interface ImportResult { importId: string; keyToId: Record<string, string>; assetIds: string[]; createdCount: number }
export interface ImportPreview {
  id: string; projectId: string; type: AssetType; parentId?: string; format: string
  status: 'READY' | 'INVALID' | 'APPLIED'; fileId: string; filename: string; checksum: string
  metadata: Record<string, unknown>; nodes: ImportNode[]; errors: ImportIssue[]; warnings: ImportIssue[]
  result?: ImportResult; createdAt: string
}
const projectPath = (project: string) => `/projects/${encodeURIComponent(project)}`
export const exchangeApi = {
  capabilities: (signal?: AbortSignal) => http.request<ExchangeCapability[]>('/exchange/capabilities', { signal }),
  templates: (signal?: AbortSignal) => http.request<TemplateFamily[]>('/templates', { signal }),
  template: (family: string, format: string, signal?: AbortSignal) => http.download(`/templates/${encodeURIComponent(family)}${queryString({ format })}`, { signal }),
  preview: (project: string, form: FormData, signal?: AbortSignal) => http.request<ImportPreview>(`${projectPath(project)}/imports/preview`, { method: 'POST', body: form, signal }),
  get: (project: string, id: string, signal?: AbortSignal) => http.request<ImportPreview>(`${projectPath(project)}/imports/${encodeURIComponent(id)}`, { signal }),
  apply: (project: string, id: string, signal?: AbortSignal) => http.request<ImportResult>(`${projectPath(project)}/imports/${encodeURIComponent(id)}/apply`, { method: 'POST', body: {}, signal }),
  export: (project: string, body: { type: AssetType; format: string; assetIds?: string[] }, signal?: AbortSignal) => http.download(`${projectPath(project)}/exports`, { method: 'POST', body, signal }),
}
