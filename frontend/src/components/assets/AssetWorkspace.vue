<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconPlus, IconRefresh, IconRobot } from '@arco-design/web-vue/es/icon'
import { useWorkspaceStore } from '../../stores/workspace'
import { assetApi } from '../../api/assets'
import type { Asset, AssetType, GenerationScope } from '../../api/types'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import AssetTable from './AssetTable.vue'
import AssetEditorDialog from './AssetEditorDialog.vue'
import AssetDetailDrawer from './AssetDetailDrawer.vue'
import AiDrawer from '../ai/AiDrawer.vue'
import EmptyState from '../common/EmptyState.vue'
import ErrorNotice from '../common/ErrorNotice.vue'
import AppTabs from '../common/AppTabs.vue'
import ExchangeActions from '../exchange/ExchangeActions.vue'
import GlobalFeedbackDrawer from '../ai/GlobalFeedbackDrawer.vue'
import RunLauncher from '../execution/RunLauncher.vue'
import RunHistoryDrawer from '../execution/RunHistoryDrawer.vue'
import { runnableTypes, type RunSubmission } from '../../api/runs'
import ModuleNavigation from '../cases/ModuleNavigation.vue'
import CaseMindmap from '../cases/CaseMindmap.vue'
import ApiDiffDrawer from '../api-diff/ApiDiffDrawer.vue'

const props = defineProps<{ title: string; description: string; types: AssetType[]; embedded?: boolean }>()
const emit = defineEmits<{ changed: [projectId: string] }>()
const workspace = useWorkspaceStore()
const activeType = ref<AssetType>(props.types[0]!)
const definition = computed(() => workspace.catalogMap.get(activeType.value))
const items = ref<Asset[]>([])
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref<unknown>()
const parentError = ref<unknown>()
const searchText = ref('')
const query = ref('')
const parentFilter = ref('ALL')
const parentOptions = ref<{ label: string; value: string }[]>([])
const modules = ref<Asset[]>([])
const caseView = ref('table')
const createVisible = ref(false)
const detailVisible = ref(false)
const selectedId = ref('')
const selectedIds = ref<string[]>([])
const detail = ref<InstanceType<typeof AssetDetailDrawer>>()
const aiVisible = ref(false)
const globalVisible = ref(false)
const apiDiffVisible = ref(false)
const aiTarget = ref<Asset>()
const generation = ref<GenerationScope>()
const runVisible = ref(false), historyVisible = ref(false), executionTarget = ref<Asset>(), initialRunId = ref('')
const hasExecution = computed(() => props.types.some(type => runnableTypes.has(type)))
const scope = new RequestScope()
const parentScope = new RequestScope()
let token: ScopeToken | undefined
const scopeKey = computed(() => `${workspace.selectedProjectId}:${activeType.value}:${parentFilter.value}:${query.value}`)
const sortable = computed(() => items.value.length > 1 && items.value.length === total.value && !query.value && new Set(items.value.map((item) => item.parentId)).size === 1)

watch(activeType, () => { parentFilter.value = 'ALL'; searchText.value = ''; query.value = '' })
watch(() => workspace.selectedProjectId, () => { parentFilter.value = 'ALL'; caseView.value = 'table'; apiDiffVisible.value = false })
watch(scopeKey, () => {
  selectedIds.value = []
  detailVisible.value = false
  aiVisible.value = false
  globalVisible.value = false
  createVisible.value = false
  runVisible.value = false
  historyVisible.value = false
  void load()
}, { immediate: true })
watch(() => [workspace.selectedProjectId, activeType.value, workspace.catalog.length], () => { void loadParents() }, { immediate: true })

async function load(append = false) {
  if (!workspace.selectedProjectId) return
  const current = scope.begin(scopeKey.value)
  token = current
  loading.value = true
  busy.value = false
  error.value = undefined
  if (!append) { items.value = []; total.value = 0 }
  try {
    const page = await assetApi.list(workspace.selectedProjectId, {
      type: activeType.value, parentId: parentFilter.value === 'ALL' ? undefined : parentFilter.value,
      q: query.value.trim() || undefined, offset: append ? items.value.length : 0, limit: 100,
    }, current.signal)
    if (!scope.isCurrent(current)) return
    const byId = new Map((append ? [...items.value, ...page.items] : page.items).map((item) => [item.id, item]))
    items.value = [...byId.values()]
    total.value = page.total
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) loading.value = false }
}
async function loadParents() {
  const projectId = workspace.selectedProjectId
  if (!projectId) return
  const current = parentScope.begin(`${projectId}:${activeType.value}`)
  parentOptions.value = []
  modules.value = []
  parentError.value = undefined
  const parentTypes = workspace.catalog.filter((entry) => entry.childTypes.includes(activeType.value))
  const results = await Promise.allSettled(parentTypes.map(async (entry) => {
    const records: Asset[] = []
    let expected = 1
    while (records.length < expected) {
      const page = await assetApi.list(projectId, { type: entry.type, offset: records.length, limit: 100 }, current.signal)
      records.push(...page.items)
      expected = page.total
      if (!page.items.length) break
    }
    return records
  }))
  if (!parentScope.isCurrent(current)) return
  const parents = results.flatMap((result) => result.status === 'fulfilled' ? result.value : [])
  parentOptions.value = parents.map(record => ({ label: `${workspace.label(record.type)} · ${record.name}`, value: record.id }))
  modules.value = parents.filter(record => record.type === 'MODULE')
  const failure = results.find((result) => result.status === 'rejected')
  if (failure?.status === 'rejected') parentError.value = failure.reason
}
function open(asset: Asset) { selectedId.value = asset.id; detailVisible.value = true }
function applyAsset(asset: Asset, updateDetail = true) {
  if (asset.projectId !== workspace.selectedProjectId) return
  const index = items.value.findIndex((item) => item.id === asset.id)
  if (index >= 0) items.value[index] = asset
  if (updateDetail) detail.value?.applyAsset(asset)
  if (aiTarget.value?.id === asset.id) aiTarget.value = asset
  if (asset.type === 'MODULE') void loadParents()
  emit('changed', asset.projectId)
}
async function mutate(asset: Asset, operation: () => Promise<Asset>) {
  const current = token
  if (!current || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    const result = await operation()
    if (!scope.isCurrent(current) || result.id !== asset.id || result.projectId !== asset.projectId) return
    applyAsset(result)
    Message.success('修改已保存')
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
function rename(asset: Asset, name: string) { void mutate(asset, () => assetApi.patch(asset.projectId, asset.id, { baseVersion: asset.version, name })) }
function confirm(asset: Asset, confirmed: boolean) { void mutate(asset, () => assetApi.patch(asset.projectId, asset.id, { baseVersion: asset.version, confirmed })) }
function removeLocal(id: string) {
  selectedIds.value = selectedIds.value.filter(selected => selected !== id)
  if (items.value.some((item) => item.id === id)) { items.value = items.value.filter((item) => item.id !== id); total.value-- }
  if (selectedId.value === id) detailVisible.value = false
  if (modules.value.some(module => module.id === id)) void loadParents()
  emit('changed', workspace.selectedProjectId)
}
async function remove(asset: Asset) {
  const current = token
  if (!current || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await assetApi.remove(asset)
    if (!scope.isCurrent(current)) return
    removeLocal(asset.id)
    Message.success('已删除')
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function reorder(ordered: Asset[]) {
  const current = token
  if (!current || !sortable.value || busy.value || !ordered[0]) return
  busy.value = true
  error.value = undefined
  try {
    const result = await assetApi.reorder(workspace.selectedProjectId, activeType.value, ordered[0].parentId, ordered)
    if (scope.isCurrent(current)) { items.value = result.sort((a, b) => a.position - b.position); emit('changed', workspace.selectedProjectId) }
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
function refine(asset: Asset) { aiTarget.value = asset; generation.value = undefined; aiVisible.value = true }
function generate() {
  if (!definition.value) return
  aiTarget.value = undefined
  generation.value = { projectId: workspace.selectedProjectId, type: activeType.value, label: definition.value.label, ...(parentFilter.value !== 'ALL' && parentFilter.value !== 'ROOT' ? { parentId: parentFilter.value } : {}) }
  aiVisible.value = true
}
function saved(asset: Asset) {
  if (asset.projectId !== workspace.selectedProjectId) return
  void load()
  if (asset.type === 'MODULE') void loadParents()
  emit('changed', asset.projectId)
}
function globalApplied(assets: Asset[], deletedIds: string[]) {
  for (const asset of assets) applyAsset(asset)
  for (const id of deletedIds) removeLocal(id)
  void load(); void loadParents()
}
function execute(asset: Asset) { executionTarget.value = asset; runVisible.value = true }
function submitted(result: RunSubmission, projectId: string) {
  if (projectId !== workspace.selectedProjectId) return
  initialRunId.value = result.runId; historyVisible.value = true; workspace.recordActivity(projectId)
}
function showRuns() { initialRunId.value = ''; historyVisible.value = true }
onUnmounted(() => { scope.invalidate(); parentScope.invalidate() })
</script>

<template>
  <section :aria-label="title">
    <div class="page-header" :style="embedded ? { marginTop: '24px' } : undefined">
      <div><h2 v-if="embedded">{{ title }}</h2><h1 v-else>{{ title }}</h1><p class="page-description">{{ description }}</p></div>
      <div v-if="workspace.selectedProjectId" class="page-actions"><a-button :disabled="!definition" @click="generate"><template #icon><IconRobot /></template>AI 生成</a-button><a-button type="primary" :disabled="!definition" @click="createVisible = true"><template #icon><IconPlus /></template>新建{{ definition?.label ?? '记录' }}</a-button></div>
    </div>
    <div v-if="!workspace.selectedProjectId" class="surface"><EmptyState title="先选择一个项目" description="测试资产按项目独立管理。创建或选择项目后，即可开始编写与维护测试。" action="前往项目管理" @action="$router.push('/projects')" /></div>
    <div v-else class="surface">
      <AppTabs v-if="types.length > 1" :model-value="activeType" :items="types.map((type) => ({ value: type, label: workspace.label(type) }))" label="资产类型" @update:model-value="activeType = $event as AssetType" />
      <div class="asset-workspace-layout" :class="{ 'with-modules': activeType === 'FUNCTIONAL_CASE' }">
      <ModuleNavigation v-if="activeType === 'FUNCTIONAL_CASE'" :modules="modules" :selected="parentFilter" :busy="busy" @select="parentFilter = $event" @edit="open" @manage="activeType = 'MODULE'" />
      <div class="asset-workspace-content">
      <div class="table-toolbar">
        <div class="toolbar-filters"><a-input-search v-model="searchText" class="table-search" :placeholder="`搜索${definition?.label ?? '记录'}`" aria-label="搜索资产" allow-clear @search="query = searchText.trim()" @clear="query = ''" /><a-select v-model="parentFilter" :options="[{ label: '全部位置', value: 'ALL' }, { label: '根节点', value: 'ROOT' }, ...parentOptions]" style="min-width: 150px; max-width: 240px" aria-label="所属位置筛选" allow-search /></div>
        <a-button type="text" :loading="loading" :disabled="busy" aria-label="刷新列表" @click="load()"><IconRefresh /></a-button>
      </div>
      <div class="table-toolbar exchange-toolbar"><ExchangeActions :project-id="workspace.selectedProjectId" :type="activeType" :parent-id="parentFilter !== 'ALL' && parentFilter !== 'ROOT' ? parentFilter : undefined" :parents="parentOptions" :selected-ids="selectedIds" @imported="load(); loadParents(); emit('changed', workspace.selectedProjectId)" /><div class="inline-actions"><a-button v-if="types.includes('API_DEFINITION')" @click="apiDiffVisible = true">接口变更对比</a-button><a-button v-if="hasExecution" @click="showRuns">执行记录</a-button><a-button @click="globalVisible = true">全局反馈</a-button><a-button v-if="selectedIds.length" type="text" size="small" @click="selectedIds = []">已选 {{ selectedIds.length }} 条 · 清除</a-button></div></div>
      <ErrorNotice v-if="error" class="inline-error" :error="error" retry @retry="load()" />
      <ErrorNotice v-if="parentError" class="inline-error" :error="parentError" retry @retry="loadParents" />
      <AppTabs v-if="activeType === 'FUNCTIONAL_CASE'" v-model="caseView" :items="[{ value: 'table', label: '列表视图' }, { value: 'mindmap', label: '脑图视图' }]" label="用例视图" />
      <div v-if="loading && !items.length" class="loading-block"><a-spin tip="正在加载测试资产…" /></div>
      <CaseMindmap v-else-if="activeType === 'FUNCTIONAL_CASE' && caseView === 'mindmap' && items.length" :assets="items" :modules="modules" :busy="busy || loading" :sortable="sortable" :selected-ids="selectedIds" @select="selectedIds = $event" @open="open" @rename="rename" @confirm="confirm" @remove="remove" @refine="refine" @reorder="reorder" @execute="execute" />
      <AssetTable v-else-if="items.length" :assets="items" :busy="busy || loading" :sortable="sortable" selectable executable :selected-ids="selectedIds" @select="selectedIds = $event" @open="open" @rename="rename" @confirm="confirm" @remove="remove" @refine="refine" @reorder="reorder" @execute="execute" />
      <EmptyState v-else-if="!error" :title="query ? '没有找到匹配记录' : `还没有${definition?.label ?? '记录'}`" :description="query ? '调整搜索条件，或清空搜索查看全部记录。' : '从一条清晰的测试记录开始，也可以让 AI 根据你的描述生成。'" :action="query ? '清空搜索' : `新建${definition?.label ?? '记录'}`" @action="query ? (query = '', searchText = '') : createVisible = true" />
      <div class="table-footer"><span>共 {{ total }} 条<span v-if="total > items.length">，已加载 {{ items.length }} 条</span></span><a-button v-if="items.length < total" size="small" :loading="loading" @click="load(true)">加载更多</a-button><span v-else>{{ sortable ? '拖动手柄或按上下方向键排序 · 双击名称编辑' : '双击名称编辑 · 单击查看详情与独立子项' }}</span></div>
      </div>
      </div>
    </div>
    <AssetEditorDialog v-if="definition" v-model:visible="createVisible" :project-id="workspace.selectedProjectId" :definition="definition" :parents="parentOptions" :parent-id="parentFilter !== 'ALL' && parentFilter !== 'ROOT' ? parentFilter : undefined" @saved="saved" />
    <AssetDetailDrawer ref="detail" v-model:visible="detailVisible" :project-id="workspace.selectedProjectId" :asset-id="selectedId" @changed="applyAsset($event, false)" @removed="removeLocal" @refine="refine" />
    <AiDrawer v-model:visible="aiVisible" :target="aiTarget" :generation="generation" @applied="applyAsset" @generated="load(); emit('changed', workspace.selectedProjectId)" />
    <GlobalFeedbackDrawer v-model:visible="globalVisible" :project-id="workspace.selectedProjectId" :asset-ids="selectedIds" @applied="globalApplied" />
    <ApiDiffDrawer v-model:visible="apiDiffVisible" :project-id="workspace.selectedProjectId" @applied="globalApplied" @open-asset="selectedId = $event; detailVisible = true" />
    <RunLauncher v-model:visible="runVisible" :target="executionTarget" @submitted="submitted" />
    <RunHistoryDrawer v-model:visible="historyVisible" :project-id="workspace.selectedProjectId" :initial-run-id="initialRunId" />
  </section>
</template>

<style scoped>
.asset-workspace-layout.with-modules { display: grid; grid-template-columns: 200px minmax(0, 1fr); }.asset-workspace-content { min-width: 0; }
@media (max-width: 960px) { .asset-workspace-layout.with-modules { grid-template-columns: minmax(0, 1fr); } }
</style>
