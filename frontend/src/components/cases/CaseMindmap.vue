<script setup lang="ts">
import { computed } from 'vue'
import { IconDelete, IconDragDotVertical, IconEdit, IconPlayArrow, IconRobot } from '@arco-design/web-vue/es/icon'
import type { Asset } from '../../api/types'
import { modulePath } from '../../core/module-tree'
import InlineAssetName from '../assets/InlineAssetName.vue'
import SafeRichText from '../common/SafeRichText.vue'
import { useAssetReorder } from '../../composables/useAssetReorder'

const props = defineProps<{ assets: Asset[]; modules: Asset[]; busy?: boolean; sortable?: boolean; selectedIds: string[] }>()
const emit = defineEmits<{ open: [asset: Asset]; rename: [asset: Asset, name: string]; refine: [asset: Asset]; remove: [asset: Asset]; confirm: [asset: Asset, confirmed: boolean]; execute: [asset: Asset]; select: [ids: string[]]; reorder: [assets: Asset[]] }>()
const { draggingId, move, dragStart, drop } = useAssetReorder(() => props.assets, () => !!props.sortable && !props.busy, assets => emit('reorder', assets))
const groups = computed(() => {
  const result = new Map<string | null, { parentId: string | null; path: string; assets: Asset[] }>()
  for (const asset of props.assets) {
    const group = result.get(asset.parentId) ?? { parentId: asset.parentId, path: modulePath(props.modules, asset.parentId), assets: [] }
    group.assets.push(asset)
    result.set(asset.parentId, group)
  }
  return [...result.values()]
})
function select(asset: Asset, selected: boolean) {
  const ids = new Set(props.selectedIds)
  if (selected) ids.add(asset.id)
  else ids.delete(asset.id)
  emit('select', [...ids])
}
</script>

<template>
  <section class="case-mindmap" role="region" aria-label="用例脑图">
    <div v-for="group in groups" :key="group.parentId ?? 'ROOT'" class="mindmap-branch">
      <div class="module-node"><strong>{{ group.path }}</strong><span class="small muted">当前加载 {{ group.assets.length }} 条</span></div>
      <div class="case-nodes">
        <article v-for="asset in group.assets" :key="asset.id" class="case-node" :data-asset-id="asset.id" :draggable="sortable && !busy" @dragstart="dragStart($event, asset)" @dragover.prevent @drop.prevent="drop(assets.findIndex(item => item.id === asset.id))" @dragend="draggingId = ''">
          <div class="case-node-heading"><a-checkbox :model-value="selectedIds.includes(asset.id)" :disabled="busy" :aria-label="`选中 ${asset.name}`" @change="select(asset, $event === true)" /><button type="button" class="drag-handle" :disabled="!sortable || busy" :aria-label="`调整 ${asset.name} 顺序，按上或下方向键移动`" @keydown.up.prevent="move(asset.id, assets.findIndex(item => item.id === asset.id) - 1)" @keydown.down.prevent="move(asset.id, assets.findIndex(item => item.id === asset.id) + 1)"><IconDragDotVertical /></button><InlineAssetName :asset="asset" :disabled="busy" @open="emit('open', asset)" @rename="emit('rename', asset, $event)" /><a-tag size="small">{{ asset.data.priority }}</a-tag><span class="mono small muted">v{{ asset.version }}</span></div>
          <SafeRichText v-if="asset.data.precondition" class="case-node-text" :value="String(asset.data.precondition)" compact />
          <SafeRichText v-if="asset.data.remark" class="case-node-text" :value="String(asset.data.remark)" compact />
          <div class="case-node-actions"><a-checkbox :model-value="asset.confirmed" :disabled="busy" :aria-label="`确认 ${asset.name}`" @change="emit('confirm', asset, $event === true)"><span class="small muted">{{ asset.confirmed ? '已确认' : '待确认' }}</span></a-checkbox><div class="inline-actions"><a-button type="text" size="mini" :disabled="busy" :aria-label="`执行 ${asset.name}`" @click="emit('execute', asset)"><IconPlayArrow /></a-button><a-button type="text" size="mini" :disabled="busy" :aria-label="`编辑 ${asset.name}`" @click="emit('open', asset)"><IconEdit /></a-button><a-button type="text" size="mini" :disabled="busy" :aria-label="`AI 优化 ${asset.name}`" @click="emit('refine', asset)"><template #icon><IconRobot /></template>局部调优</a-button><a-popconfirm :content="`删除「${asset.name}」及其子项？此操作不可撤销。`" @ok="emit('remove', asset)"><a-button type="text" size="mini" status="danger" :disabled="busy" :aria-label="`删除 ${asset.name}`"><IconDelete /></a-button></a-popconfirm></div></div>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.case-mindmap { padding: 24px; overflow: auto; background: radial-gradient(#e6e7ef 1px, transparent 1px); background-size: 18px 18px; }
.mindmap-branch { display: grid; grid-template-columns: minmax(120px, 180px) minmax(300px, 1fr); align-items: start; gap: 28px; margin-bottom: 24px; }.mindmap-branch:last-child { margin-bottom: 0; }
.module-node { position: relative; display: grid; gap: 8px; padding: 15px; border: 1px solid #cbc8f6; border-radius: 10px; background: #f4f2ff; overflow-wrap: anywhere; font-size: 13px; }
.module-node::after { position: absolute; content: ''; width: 28px; height: 1px; background: #cbc8f6; top: 28px; right: -29px; }.case-nodes { border-left: 1px solid #cbc8f6; padding-left: 22px; min-width: 0; }
.case-node { position: relative; padding: 12px 14px; background: var(--color-bg-2, white); border: 1px solid var(--border); border-radius: 9px; margin-bottom: 12px; min-width: 0; }.case-node:last-child { margin-bottom: 0; }.case-node::before { position: absolute; content: ''; width: 22px; height: 1px; background: #cbc8f6; left: -23px; top: 28px; }
.case-node-heading, .case-node-actions { display: flex; align-items: center; gap: 7px; }.case-node-heading :deep(.asset-name) { flex: 1; min-width: 0; overflow-wrap: anywhere; }.case-node-actions { justify-content: space-between; margin-top: 12px; flex-wrap: wrap; }.case-node-text { font-size: 12px; color: var(--muted); margin: 8px 0; }.mono { white-space: nowrap; }
@media (max-width: 1200px) { .mindmap-branch { grid-template-columns: minmax(0, 1fr); gap: 14px; }.module-node::after { display: none; }.case-mindmap { padding: 16px; } }
@media (max-width: 600px) { .case-node-heading { flex-wrap: wrap; }.case-node-heading :deep(.asset-name) { flex-basis: calc(100% - 70px); }.case-node-text { white-space: normal; }.case-node-actions { align-items: start; flex-direction: column; gap: 10px; }.case-node-actions .inline-actions { flex-wrap: wrap; } }
</style>
