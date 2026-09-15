import type { Asset } from '../api/types'

export interface ModuleRow { asset: Asset; depth: number; path: string }
const byPosition = (left: Asset, right: Asset) => left.position - right.position || left.id.localeCompare(right.id)

export function modulePath(modules: Asset[], id: string | null): string {
  if (!id) return '未分类'
  const index = new Map(modules.map(module => [module.id, module]))
  const visited = new Set<string>()
  const path: string[] = []
  let cursor: string | null = id
  while (cursor && !visited.has(cursor)) {
    visited.add(cursor)
    const module = index.get(cursor)
    if (!module) break
    path.unshift(module.name)
    cursor = module.parentId
  }
  return path.length ? path.join(' / ') : '所属模块未加载'
}

export function moduleRows(modules: Asset[]): ModuleRow[] {
  const index = new Map(modules.map(module => [module.id, module]))
  const children = new Map<string | null, Asset[]>()
  for (const module of index.values()) {
    const parent = module.parentId && index.has(module.parentId) ? module.parentId : null
    const siblings = children.get(parent) ?? []
    siblings.push(module)
    children.set(parent, siblings)
  }
  for (const siblings of children.values()) siblings.sort(byPosition)
  const result: ModuleRow[] = [], visited = new Set<string>()
  function append(root: Asset) {
    const pending = [{ asset: root, depth: 0, path: root.name }]
    while (pending.length) {
      const node = pending.pop()!
      if (visited.has(node.asset.id)) continue
      visited.add(node.asset.id)
      result.push(node)
      for (const child of [...(children.get(node.asset.id) ?? [])].reverse()) pending.push({ asset: child, depth: node.depth + 1, path: `${node.path} / ${child.name}` })
    }
  }
  for (const root of children.get(null) ?? []) append(root)
  // A corrupt or partially loaded hierarchy still shows each record exactly once.
  for (const module of [...index.values()].sort(byPosition)) if (!visited.has(module.id)) append(module)
  return result
}
