<script setup lang="ts">
import { computed } from 'vue'
import type { ChangeItem, ChangeSet } from '../../api/feedback'
import { displayValue } from '../../core/format'
import { localAssetReferences } from '../../core/asset-references'
import { useWorkspaceStore } from '../../stores/workspace'

const props = defineProps<{ changeSet: ChangeSet; busy?: boolean }>()
const selectedIds = defineModel<string[]>('selectedIds', { default: () => [] })
defineEmits<{ apply: []; reject: [] }>()
const workspace = useWorkspaceStore()
const editable = computed(() => props.changeSet.status === 'DRAFT' && !props.busy)
const operationLabels = { ADD: '新增', MODIFY: '修改', DELETE: '删除' }
const title = (item: ChangeItem) => item.after.name ?? item.before?.name ?? item.localKey ?? item.targetId ?? '新资产'
function fields(item: ChangeItem) {
  const entries = Object.entries(item.after.data).map(([key, value]) => ({ key, before: item.before?.data[key], after: value }))
  if (item.after.name !== null && item.after.name !== item.before?.name) entries.unshift({ key: 'name', before: item.before?.name, after: item.after.name })
  return entries.filter(entry => JSON.stringify(entry.before) !== JSON.stringify(entry.after))
}
function toggle(id: string, selected: boolean) {
  selectedIds.value = selected ? [...new Set([...selectedIds.value, id])] : selectedIds.value.filter(value => value !== id)
}
const missingDependencies = computed(() => {
  const byKey = new Map(props.changeSet.items.filter(item => item.localKey).map(item => [item.localKey!, item]))
  const missing = new Map<string, string>()
  for (const item of props.changeSet.items.filter(item => selectedIds.value.includes(item.id))) {
    for (const key of localAssetReferences(item.parentId, item.after.data)) {
      const dependency = byKey.get(key)
      if (dependency && !selectedIds.value.includes(dependency.id)) missing.set(dependency.id, title(dependency))
    }
    if (item.operation === 'DELETE') {
      for (const child of props.changeSet.items.filter(child => child.operation === 'DELETE' && child.parentId === item.targetId)) {
        if (!selectedIds.value.includes(child.id)) missing.set(child.id, title(child))
      }
    }
  }
  return [...missing.values()]
})
</script>

<template>
  <section class="change-review" aria-label="候选变更预览">
    <div class="change-heading"><h3>候选变更</h3><a-tag>{{ { DRAFT: '等待采纳', APPLIED: '已采纳', REJECTED: '已拒绝' }[changeSet.status] }}</a-tag></div>
    <p class="small muted">核对字段差异并选择要采纳的项目。未选中的候选不会写入资产。</p>
    <a-checkbox v-if="editable" :model-value="selectedIds.length === changeSet.items.length" aria-label="选中全部候选" @change="selectedIds = $event ? changeSet.items.map(item => item.id) : []">选中全部 {{ changeSet.items.length }} 项</a-checkbox>
    <article v-for="item in changeSet.items" :key="item.id" class="change-card" :data-change-id="item.id" :data-change-operation="item.operation">
      <div class="change-card-heading">
        <a-checkbox :model-value="selectedIds.includes(item.id)" :disabled="!editable" :aria-label="`选择${operationLabels[item.operation]} ${title(item)}`" @change="toggle(item.id, $event === true)" />
        <a-tag :color="item.operation === 'DELETE' ? 'red' : item.operation === 'ADD' ? 'green' : 'arcoblue'">{{ operationLabels[item.operation] }}</a-tag>
        <strong>{{ title(item) }}</strong><span class="small muted">{{ workspace.label(item.targetType) }}<template v-if="item.baseVersion"> · 基于 v{{ item.baseVersion }}</template></span>
      </div>
      <p v-if="item.operation === 'DELETE'" class="small">采纳后删除此记录。父子节点与引用仍由服务器逐项检查。</p>
      <div v-else v-for="field in fields(item)" :key="field.key" class="change-field">
        <strong class="small">{{ field.key === 'name' ? '名称' : workspace.catalogMap.get(item.targetType)?.fields.find(entry => entry.key === field.key)?.label ?? field.key }}</strong>
        <div class="change-values"><div><span class="small muted">当前内容</span><pre>{{ displayValue(field.before) }}</pre></div><div><span class="small muted">候选内容</span><pre>{{ displayValue(field.after) }}</pre></div></div>
      </div>
    </article>
    <a-alert v-if="missingDependencies.length" type="warning">还需选择依赖项：{{ missingDependencies.join('、') }}</a-alert>
    <div v-if="changeSet.status === 'DRAFT'" class="inline-actions change-buttons"><a-button type="primary" :disabled="!selectedIds.length || missingDependencies.length > 0" :loading="busy" @click="$emit('apply')">采纳选中 {{ selectedIds.length }} 项</a-button><a-button :disabled="busy" @click="$emit('reject')">拒绝本轮候选</a-button></div>
  </section>
</template>

<style scoped>
.change-review { margin-block: 20px; }
.change-heading, .change-card-heading { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.change-heading h3 { margin: 0; }
.change-card { border: 1px solid var(--border); border-radius: 10px; padding: 16px; margin-top: 12px; }
.change-field { margin-top: 14px; }
.change-values { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 8px; }
.change-values > div { min-width: 0; background: var(--surface-muted); padding: 12px; border-radius: 6px; }
.change-values > div:last-child { background: var(--success-soft); }
.change-values pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 280px; overflow: auto; margin: 8px 0 0; font-size: 12px; line-height: 1.7; }
.change-buttons { margin-top: 16px; }
@media(max-width: 600px) { .change-values { grid-template-columns: 1fr; } }
</style>
