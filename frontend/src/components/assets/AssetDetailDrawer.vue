<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconArrowLeft, IconPlus, IconRefresh, IconRobot, IconSave } from '@arco-design/web-vue/es/icon'
import { assetApi } from '../../api/assets'
import type { Asset, AssetType, Revision } from '../../api/types'
import { useWorkspaceStore } from '../../stores/workspace'
import { buildAssetPatch } from '../../core/asset-patch'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { formatTime } from '../../core/format'
import CatalogForm from './CatalogForm.vue'
import AssetTable from './AssetTable.vue'
import AssetEditorDialog from './AssetEditorDialog.vue'
import ErrorNotice from '../common/ErrorNotice.vue'
import EmptyState from '../common/EmptyState.vue'
import AppTabs from '../common/AppTabs.vue'
import CaseContent from '../cases/CaseContent.vue'
import StepCards from './StepCards.vue'
import BugOccurrenceHistory from '../bugs/BugOccurrenceHistory.vue'
import QualityBriefContent from '../reports/QualityBriefContent.vue'
import PlanSchedulePanel from '../schedule/PlanSchedulePanel.vue'
import CodeDiagnosis from '../bugs/CodeDiagnosis.vue'
import NotificationHistory from '../notifications/NotificationHistory.vue'

const props = defineProps<{ projectId: string; assetId: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ changed: [asset: Asset]; refine: [asset: Asset]; removed: [id: string] }>()
const workspace = useWorkspaceStore()
const currentId = ref('')
const asset = ref<Asset>()
const children = ref<Asset[]>([])
const history = ref<Revision[]>([])
const trail = ref<{ id: string; name: string }[]>([])
const tab = ref('fields')
const childType = ref<AssetType>()
const childView = ref('cards')
const supportsCards = computed(() => !!childType.value && ['UI_STEP', 'FUNCTIONAL_STEP', 'SCENARIO_STEP', 'SQL_VALIDATION'].includes(childType.value))
const createChild = ref(false)
const form = ref<InstanceType<typeof CatalogForm>>()
const loading = ref(false)
const busy = ref(false)
const error = ref<unknown>()
const childrenError = ref<unknown>()
const historyError = ref<unknown>()
const scope = new RequestScope()
const historyScope = new RequestScope()
let token: ScopeToken | undefined
const definition = computed(() => asset.value ? workspace.catalogMap.get(asset.value.type) : undefined)
const childDefinition = computed(() => childType.value ? workspace.catalogMap.get(childType.value) : undefined)
const visibleChildren = computed(() => children.value.filter((child) => child.type === childType.value).sort((a, b) => a.position - b.position))

watch(() => [visible.value, props.assetId, props.projectId], () => {
  trail.value = []
  tab.value = 'fields'
  currentId.value = props.assetId
  if (!visible.value) { scope.invalidate(); historyScope.invalidate(); asset.value = undefined }
  else void load()
}, { immediate: true })

async function load() {
  if (!visible.value || !props.projectId || !currentId.value) return
  const current = scope.begin(`${props.projectId}:${currentId.value}`)
  historyScope.invalidate()
  token = current
  if (asset.value?.id !== currentId.value || asset.value.projectId !== props.projectId) asset.value = undefined
  children.value = []
  history.value = []
  error.value = undefined
  childrenError.value = undefined
  historyError.value = undefined
  loading.value = true
  busy.value = false
  const results = await Promise.allSettled([
    assetApi.get(props.projectId, currentId.value, current.signal),
    assetApi.children(props.projectId, currentId.value, current.signal),
    assetApi.history(props.projectId, currentId.value, current.signal),
  ])
  if (!scope.isCurrent(current)) return
  const [detail, childResult, historyResult] = results
  if (detail.status === 'fulfilled') {
    asset.value = detail.value
    const types = workspace.catalogMap.get(detail.value.type)?.childTypes ?? []
    if (!childType.value || !types.includes(childType.value)) childType.value = types[0]
  } else error.value = detail.reason
  if (childResult.status === 'fulfilled') children.value = childResult.value
  else childrenError.value = childResult.reason
  if (historyResult.status === 'fulfilled') history.value = historyResult.value
  else historyError.value = historyResult.reason
  loading.value = false
}
function navigate(child: Asset) {
  if (asset.value) trail.value.push({ id: asset.value.id, name: asset.value.name })
  currentId.value = child.id
  tab.value = 'fields'
  void load()
}
function back() {
  const previous = trail.value.pop()
  if (previous) { currentId.value = previous.id; tab.value = 'children'; void load() }
}
async function refreshHistory(current: ScopeToken, id: string) {
  const historyToken = historyScope.begin(id)
  try {
    const result = await assetApi.history(props.projectId, id, AbortSignal.any([current.signal, historyToken.signal]))
    if (scope.isCurrent(current) && historyScope.isCurrent(historyToken) && currentId.value === id) { history.value = result; historyError.value = undefined }
  } catch (failure) { if (scope.isCurrent(current) && historyScope.isCurrent(historyToken)) historyError.value = failure }
}
function applyAsset(updated: Asset) {
  if (!visible.value || updated.projectId !== props.projectId) return
  if (asset.value?.id === updated.id) {
    asset.value = updated
    if (token) void refreshHistory(token, updated.id)
  }
  const index = children.value.findIndex((child) => child.id === updated.id)
  if (index >= 0) children.value[index] = updated
}
async function mutate(target: Asset, operation: () => Promise<Asset>) {
  const current = token
  if (!current || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    const updated = await operation()
    if (!scope.isCurrent(current) || updated.id !== target.id || updated.projectId !== target.projectId) return
    applyAsset(updated)
    emit('changed', updated)
    Message.success('修改已保存')
    return updated
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function save() {
  const target = asset.value
  const draft = form.value?.read()
  if (!target || !draft || !definition.value) return
  const patch = buildAssetPatch(form.value?.baseline ?? target, draft, definition.value.fields.map((field) => field.key))
  if (!patch) { Message.info('没有需要保存的修改'); return }
  const updated = await mutate(target, () => assetApi.patch(target.projectId, target.id, patch))
  if (updated) form.value?.reset(updated)
}
function rename(target: Asset, name: string) { void mutate(target, () => assetApi.patch(target.projectId, target.id, { baseVersion: target.version, name })) }
function confirm(target: Asset, confirmed: boolean) { void mutate(target, () => assetApi.patch(target.projectId, target.id, { baseVersion: target.version, confirmed })) }
async function remove(target: Asset) {
  const current = token
  if (!current || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await assetApi.remove(target)
    if (!scope.isCurrent(current)) return
    children.value = children.value.filter((child) => child.id !== target.id)
    emit('removed', target.id)
    Message.success('已删除')
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function reorder(ordered: Asset[]) {
  const current = token
  const parent = asset.value
  const type = childType.value
  if (!current || !parent || !type || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    const result = await assetApi.reorder(parent.projectId, type, parent.id, ordered)
    if (!scope.isCurrent(current)) return
    const ids = new Set(result.map((item) => item.id))
    children.value = [...children.value.filter((child) => !ids.has(child.id)), ...result]
    for (const updated of result) emit('changed', updated)
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
function childSaved(child: Asset) {
  if (child.projectId !== props.projectId || child.parentId !== currentId.value) return
  children.value = [...children.value.filter((record) => record.id !== child.id), child]
  emit('changed', child)
}
function undo(revision: Revision) {
  const target = asset.value
  if (target) void mutate(target, () => assetApi.undo(target, revision.id))
}
defineExpose({ applyAsset })
onUnmounted(() => { scope.invalidate(); historyScope.invalidate() })
</script>

<template>
  <a-drawer v-model:visible="visible" :title="asset?.name ?? '资产详情'" role="dialog" aria-modal="true" :aria-label="asset?.name ?? '资产详情'" :width="860" :footer="false" unmount-on-close>
    <a-button v-if="trail.length" type="text" style="margin-bottom: 14px" @click="back"><template #icon><IconArrowLeft /></template>返回 {{ trail[trail.length - 1]?.name }}</a-button>
    <div v-if="loading" class="loading-block"><a-spin tip="加载记录…" /></div>
    <ErrorNotice :error="error" retry style="margin-bottom: 18px" @retry="load" />
    <template v-if="asset && definition">
      <div class="detail-meta"><a-tag color="arcoblue">{{ definition.label }}</a-tag><span class="mono">{{ asset.id }}</span><span>版本 {{ asset.version }}</span><span>更新于 {{ formatTime(asset.updatedAt) }}</span></div>
      <div class="detail-toolbar">
        <a-checkbox :model-value="asset.confirmed" :disabled="busy" @change="confirm(asset, $event === true)">已确认</a-checkbox>
        <div class="inline-actions"><a-button size="small" :disabled="busy" aria-label="刷新当前记录" @click="load"><IconRefresh /></a-button><a-button size="small" @click="emit('refine', asset)"><template #icon><IconRobot /></template>AI 优化当前记录</a-button><a-button v-if="tab === 'fields'" type="primary" size="small" :loading="busy" @click="save"><template #icon><IconSave /></template>保存修改</a-button></div>
      </div>
      <AppTabs v-model="tab" :items="[{ value: 'fields', label: '详情与编辑' }, ...(definition.childTypes.length ? [{ value: 'children', label: `独立子项 · ${children.length}` }] : []), ...(asset.type === 'BUG' ? [{ value: 'code', label: '源码诊断' }, { value: 'occurrences', label: '发生记录' }] : []), ...(asset.type === 'TEST_PLAN' ? [{ value: 'schedule', label: '巡检排期' }] : []), ...(asset.type === 'WEBHOOK' ? [{ value: 'deliveries', label: '发送记录' }] : []), { value: 'history', label: '版本历史' }]" label="记录详情" style="padding: 0" />
      <div class="detail-panels">
        <div v-show="tab === 'fields'" role="tabpanel" aria-label="详情与编辑"><QualityBriefContent v-if="asset.type === 'QUALITY_BRIEF'" :asset="asset" /><details v-if="asset.type === 'FUNCTIONAL_CASE'" class="case-preview" open><summary>用例内容预览</summary><section role="region" aria-label="用例内容预览"><CaseContent :asset="asset" :steps="children.filter(child => child.type === 'FUNCTIONAL_STEP').sort((a, b) => a.position - b.position)" /></section></details><CatalogForm ref="form" :definition="definition" :asset="asset" :disabled="busy" /></div>
        <div v-if="asset.type === 'BUG' && tab === 'occurrences'" role="tabpanel" aria-label="发生记录"><BugOccurrenceHistory :project-id="projectId" :bug-id="asset.id" /></div>
        <div v-if="asset.type === 'BUG'" v-show="tab === 'code'" role="tabpanel" aria-label="源码诊断"><CodeDiagnosis :asset="asset" @changed="updated => { applyAsset(updated); emit('changed', updated) }" /></div>
        <div v-if="asset.type === 'TEST_PLAN'" v-show="tab === 'schedule'" role="tabpanel" aria-label="巡检排期"><PlanSchedulePanel :asset="asset" @changed="updated => { applyAsset(updated); emit('changed', updated) }" /></div>
        <div v-if="asset.type === 'WEBHOOK'" v-show="tab === 'deliveries'" role="tabpanel" aria-label="发送记录"><NotificationHistory :asset="asset" @changed="updated => { applyAsset(updated); emit('changed', updated) }" /></div>
        <div v-if="definition.childTypes.length" v-show="tab === 'children'" role="tabpanel" aria-label="独立子项">
          <ErrorNotice :error="childrenError" retry @retry="load" />
          <div class="detail-toolbar"><a-select v-model="childType" :options="definition.childTypes.map((type) => ({ label: workspace.label(type), value: type }))" style="width: 200px" aria-label="子项类型" /><a-button type="primary" size="small" @click="createChild = true"><template #icon><IconPlus /></template>添加{{ childDefinition?.label }}</a-button></div>
          <AppTabs v-if="supportsCards" v-model="childView" :items="[{ value: 'cards', label: '步骤卡片' }, { value: 'table', label: '步骤列表' }]" label="子项视图" />
          <div class="surface"><StepCards v-if="supportsCards && childView === 'cards' && visibleChildren.length" :assets="visibleChildren" :busy="busy" sortable @open="navigate" @rename="rename" @confirm="confirm" @remove="remove" @refine="emit('refine', $event)" @reorder="reorder" /><AssetTable v-else-if="visibleChildren.length" :assets="visibleChildren" :busy="busy" :sortable="true" compact @open="navigate" @rename="rename" @confirm="confirm" @remove="remove" @refine="emit('refine', $event)" @reorder="reorder" /><EmptyState v-else-if="!childrenError" :title="`还没有${childDefinition?.label ?? '子项'}`" description="每个子项独立保存，可单独编辑、排序和使用 AI 优化。" /></div>
          <p class="small muted" style="margin-top: 12px">拖动行前的手柄调整顺序，或聚焦手柄后按上下方向键。</p>
        </div>
        <div v-show="tab === 'history'" role="tabpanel" aria-label="版本历史">
          <ErrorNotice :error="historyError" retry @retry="load" />
          <EmptyState v-if="!history.length && !historyError" title="暂无版本记录" description="保存或 AI 优化后，可在这里查看版本与恢复历史内容。" />
          <article v-for="revision in history" :key="revision.id" class="history-item">
            <div class="history-title"><strong>版本 {{ revision.version }} <a-tag v-if="revision.version === asset.version" size="small">当前</a-tag></strong><a-popconfirm v-if="revision.version !== asset.version" content="恢复此版本的内容？恢复后会生成一个新版本。" @ok="undo(revision)"><a-button type="text" size="small" :disabled="busy">恢复此版本</a-button></a-popconfirm></div>
            <p class="history-meta">{{ revision.operation }} · {{ revision.source }} · {{ formatTime(revision.createdAt) }}</p>
            <details><summary class="small muted">查看历史快照</summary><pre class="json-view">{{ JSON.stringify(revision.snapshot, null, 2) }}</pre></details>
          </article>
        </div>
      </div>
      <AssetEditorDialog v-if="childDefinition" v-model:visible="createChild" :project-id="projectId" :definition="childDefinition" :parent-id="asset.id" @saved="childSaved" />
    </template>
  </a-drawer>
</template>

<style scoped>.detail-panels { padding-top: 22px; }.case-preview { margin-bottom: 22px; padding: 14px; background: var(--color-fill-1); border: 1px solid var(--border); border-radius: 8px; }.case-preview summary { cursor: pointer; font-size: 13px; font-weight: 600; }</style>
