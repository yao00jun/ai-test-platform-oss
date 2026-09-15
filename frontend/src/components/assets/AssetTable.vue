<script setup lang="ts">
import { IconDragDotVertical, IconRobot, IconDelete, IconEdit, IconPlayArrow } from '@arco-design/web-vue/es/icon'
import { runnableTypes } from '../../api/runs'
import type { Asset } from '../../api/types'
import { formatTime } from '../../core/format'
import { useWorkspaceStore } from '../../stores/workspace'
import InlineAssetName from './InlineAssetName.vue'
import SafeRichText from '../common/SafeRichText.vue'
import { useAssetReorder } from '../../composables/useAssetReorder'

const props = defineProps<{ assets: Asset[]; busy?: boolean; sortable?: boolean; compact?: boolean; selectable?: boolean; selectedIds?: string[]; executable?: boolean; showType?: boolean }>()
const workspace = useWorkspaceStore()
const emit = defineEmits<{
  open: [asset: Asset]
  rename: [asset: Asset, name: string]
  confirm: [asset: Asset, confirmed: boolean]
  remove: [asset: Asset]
  refine: [asset: Asset]
  reorder: [assets: Asset[]]
  select: [ids: string[]]
  execute: [asset: Asset]
}>()
const { draggingId, move, dragStart, drop } = useAssetReorder(() => props.assets, () => !!props.sortable && !props.busy, assets => emit('reorder', assets))
const sourceLabels = { MANUAL: '手动', AI: 'AI', IMPORT: '导入' }
function subtitle(asset: Asset) {
  const data = asset.data
  if (typeof data.path === 'string') return `${typeof data.method === 'string' ? data.method : ''} ${data.path}`.trim()
  return [data.description, data.precondition, data.step, data.selector, data.reproduceSteps, data.content].find((value) => typeof value === 'string' && value.trim()) as string | undefined
}
function select(ids: string[], selected: boolean) {
  const values = new Set(props.selectedIds ?? [])
  for (const id of ids) { if (selected) values.add(id); else values.delete(id) }
  emit('select', [...values])
}
</script>

<template>
  <div class="table-overflow">
    <table class="asset-table" aria-label="测试资产列表">
      <thead><tr><th v-if="selectable" style="width: 38px"><a-checkbox :model-value="assets.length > 0 && assets.every(asset => selectedIds?.includes(asset.id))" :disabled="busy" aria-label="选中当前已加载记录" @change="select(assets.map(asset => asset.id), $event === true)" /></th><th style="width: 44px"><span class="sr-only">排序</span></th><th class="name-cell">名称</th><th>确认状态</th><th v-if="!compact">来源</th><th>版本</th><th v-if="!compact">最近更新</th><th :style="{ width: executable ? '206px' : '154px' }">操作</th></tr></thead>
      <tbody>
        <tr v-for="(asset, index) in assets" :key="asset.id" :class="{ 'is-dragging': draggingId === asset.id }" :data-asset-id="asset.id" :draggable="sortable && !busy" @dragstart="dragStart($event, asset)" @dragover.prevent @drop.prevent="drop(index)" @dragend="draggingId = ''">
          <td v-if="selectable"><a-checkbox :model-value="selectedIds?.includes(asset.id) ?? false" :disabled="busy" :aria-label="`选中 ${asset.name}`" @change="select([asset.id], $event === true)" /></td>
          <td><button type="button" class="drag-handle" :disabled="!sortable || busy" :aria-label="`调整 ${asset.name} 顺序，按上或下方向键移动`" title="拖动排序，也可聚焦后按上下方向键" @keydown.up.prevent="move(asset.id, index - 1)" @keydown.down.prevent="move(asset.id, index + 1)"><IconDragDotVertical /></button></td>
          <td class="name-cell"><a-tag v-if="showType" size="small" color="gray" style="margin-bottom: 6px">{{ workspace.label(asset.type) }}</a-tag><InlineAssetName :asset="asset" :disabled="busy" @open="emit('open', asset)" @rename="emit('rename', asset, $event)" /><SafeRichText v-if="subtitle(asset)" class="asset-subtitle" :value="subtitle(asset)" compact /></td>
          <td><a-checkbox :model-value="asset.confirmed" :disabled="busy" :aria-label="`确认 ${asset.name}`" @change="emit('confirm', asset, $event === true)"><span class="small" :class="{ muted: !asset.confirmed }">{{ asset.confirmed ? '已确认' : '待确认' }}</span></a-checkbox></td>
          <td v-if="!compact"><a-tag size="small" :color="asset.source === 'AI' ? 'arcoblue' : 'gray'">{{ sourceLabels[asset.source] ?? asset.source }}</a-tag></td>
          <td class="mono muted">v{{ asset.version }}</td>
          <td v-if="!compact" class="small muted" style="white-space: nowrap">{{ formatTime(asset.updatedAt) }}</td>
          <td><div class="row-actions">
            <a-button v-if="executable && runnableTypes.has(asset.type)" type="text" size="small" :disabled="busy" :aria-label="`执行 ${asset.name}`" @click="emit('execute', asset)"><template #icon><IconPlayArrow /></template>执行</a-button>
            <a-tooltip content="编辑与子项"><a-button type="text" size="small" :disabled="busy" :aria-label="`编辑 ${asset.name}`" @click="emit('open', asset)"><IconEdit /></a-button></a-tooltip>
            <a-button type="text" size="small" :disabled="busy" :aria-label="`AI 优化 ${asset.name}`" @click="emit('refine', asset)"><template #icon><IconRobot /></template>优化</a-button>
            <a-popconfirm :content="`删除「${asset.name}」及其子项？此操作不可撤销。`" type="warning" ok-text="删除" @ok="emit('remove', asset)"><a-button type="text" status="danger" size="small" :disabled="busy" :aria-label="`删除 ${asset.name}`"><IconDelete /></a-button></a-popconfirm>
          </div></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }</style>
