import { http, queryString } from './client'
import type { Asset, AssetPatch, AssetType, CatalogType, Page, ProjectSummary, Revision } from './types'

const projectPath = (projectId: string) => `/projects/${encodeURIComponent(projectId)}`
const assetPath = (projectId: string, id: string) => `${projectPath(projectId)}/assets/${encodeURIComponent(id)}`

export const assetApi = {
  catalog: (signal?: AbortSignal) => http.request<{ types: CatalogType[] }>('/catalog', { signal }),
  projects: (signal?: AbortSignal) => http.request<Asset[]>('/projects', { signal }),
  createProject: (name: string, data: Record<string, unknown>) => http.request<Asset>('/projects', { method: 'POST', body: { name, data } }),
  list: (projectId: string, params: { type: AssetType; parentId?: string; q?: string; offset?: number; limit?: number }, signal?: AbortSignal) =>
    http.request<Page<Asset>>(`${projectPath(projectId)}/assets${queryString(params)}`, { signal }),
  get: (projectId: string, id: string, signal?: AbortSignal) => http.request<Asset>(assetPath(projectId, id), { signal }),
  create: (projectId: string, body: { type: AssetType; name: string; parentId?: string; data: Record<string, unknown> }) =>
    http.request<Asset>(`${projectPath(projectId)}/assets`, { method: 'POST', body }),
  patch: (projectId: string, id: string, body: AssetPatch) => http.request<Asset>(assetPath(projectId, id), { method: 'PATCH', body }),
  remove: (asset: Asset) => http.request<void>(`${assetPath(asset.projectId, asset.id)}${queryString({ baseVersion: asset.version })}`, { method: 'DELETE' }),
  children: (projectId: string, id: string, signal?: AbortSignal) => http.request<Asset[]>(`${assetPath(projectId, id)}/children`, { signal }),
  history: (projectId: string, id: string, signal?: AbortSignal) => http.request<Revision[]>(`${assetPath(projectId, id)}/history`, { signal }),
  undo: (asset: Asset, revisionId: string) => http.request<Asset>(`${assetPath(asset.projectId, asset.id)}/undo`, { method: 'POST', body: { baseVersion: asset.version, revisionId } }),
  reorder: (projectId: string, type: AssetType, parentId: string | null, assets: Asset[]) => http.request<Asset[]>(`${projectPath(projectId)}/assets/reorder`, {
    method: 'POST', body: { type, ...(parentId ? { parentId } : {}), items: assets.map(({ id, version }) => ({ id, baseVersion: version })) },
  }),
  summary: (projectId: string, signal?: AbortSignal) => http.request<ProjectSummary>(`${projectPath(projectId)}/summary`, { signal }),
}

export async function allAssets(projectId: string, type: AssetType, signal?: AbortSignal): Promise<Asset[]> {
  const records = new Map<string, Asset>()
  let offset = 0
  while (true) {
    const page = await assetApi.list(projectId, { type, offset, limit: 100 }, signal)
    for (const asset of page.items) records.set(asset.id, asset)
    offset += page.items.length
    if (!page.items.length || offset >= page.total) return [...records.values()]
  }
}
