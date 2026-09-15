import { ref } from 'vue'
import type { Asset } from '../api/types'

/** Shared by the list, mindmap and step cards; the owner supplies the complete CAS reorder scope. */
export function useAssetReorder(assets: () => Asset[], allowed: () => boolean, reordered: (assets: Asset[]) => void) {
  const draggingId = ref('')
  function move(id: string, targetIndex: number) {
    if (!allowed() || !Number.isInteger(targetIndex) || targetIndex < 0 || targetIndex >= assets().length) return
    const ordered = [...assets()]
    const index = ordered.findIndex(item => item.id === id)
    if (index < 0 || index === targetIndex) return
    ordered.splice(targetIndex, 0, ...ordered.splice(index, 1))
    reordered(ordered)
  }
  function dragStart(event: DragEvent, asset: Asset) {
    if (!allowed()) { event.preventDefault(); return }
    draggingId.value = asset.id
    if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', asset.id) }
  }
  function drop(index: number) { if (draggingId.value) move(draggingId.value, index); draggingId.value = '' }
  return { draggingId, move, dragStart, drop }
}
