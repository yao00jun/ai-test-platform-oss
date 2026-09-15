import type { Asset, AssetDraft, AssetPatch } from '../api/types'

function equal(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((value, index) => equal(value, right[index]))
  if (left && right && typeof left === 'object' && typeof right === 'object' && !Array.isArray(left) && !Array.isArray(right)) {
    const a = left as Record<string, unknown>
    const b = right as Record<string, unknown>
    return Object.keys(a).length === Object.keys(b).length && Object.keys(a).every((key) => Object.hasOwn(b, key) && equal(a[key], b[key]))
  }
  return false
}

/** Only explicit editable data keys are patchable; independent children never come from a parent form. */
export function buildAssetPatch(asset: Asset, draft: Partial<AssetDraft>, editableKeys: string[] = []): AssetPatch | null {
  const patch: AssetPatch = { baseVersion: asset.version }
  if (draft.name !== undefined && draft.name.trim() !== asset.name) patch.name = draft.name.trim()
  if (draft.confirmed !== undefined && draft.confirmed !== asset.confirmed) patch.confirmed = draft.confirmed
  if (draft.data) {
    const changed: Record<string, unknown> = {}
    for (const key of editableKeys) {
      if (Object.hasOwn(draft.data, key) && !equal(asset.data[key], draft.data[key])) changed[key] = draft.data[key]
    }
    if (Object.keys(changed).length) patch.data = changed
  }
  return Object.keys(patch).length > 1 ? patch : null
}
