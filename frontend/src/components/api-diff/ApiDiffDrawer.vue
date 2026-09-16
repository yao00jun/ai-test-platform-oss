<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { apiDiffApi, type ApiDiff, type ApiDiffApply, type ApiDiffItem, type ApiDiffSummary } from '../../api/api-diffs'
import { exchangeApi } from '../../api/exchange'
import { assetApi } from '../../api/assets'
import type { Asset } from '../../api/types'
import { ApiError } from '../../api/client'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { displayValue, formatTime } from '../../core/format'
import ErrorNotice from '../common/ErrorNotice.vue'
import GlobalFeedbackDrawer from '../ai/GlobalFeedbackDrawer.vue'

const props = defineProps<{ projectId: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ applied: [assets: Asset[], removedIds: string[]]; openAsset: [id: string] }>()
const scope = new RequestScope()
let token: ScopeToken | undefined, pendingApply: { diffId: string; input: ApiDiffApply } | undefined
const diff = ref<ApiDiff>(), summaries = ref<ApiDiffSummary[]>([]), total = ref(0), definitions = ref<Asset[]>([])
const selected = ref<string[]>([]), mappings = ref<Record<string, string>>({}), file = ref<File>(), importId = ref(''), format = ref('json')
const busy = ref(false), error = ref<unknown>(), notice = ref(''), healingVisible = ref(false), showUnchanged = ref(false), page = ref(1), inputKey = ref(0)
const relatedNames = ref<Record<string, string>>({})
const labels = { ADDED: '新增', REMOVED: '删除', CHANGED: '变更', UNCHANGED: '未变化', AMBIGUOUS: '需要映射' }
const statusLabels = { READY: '等待采纳', UNCHANGED: '没有变化', PARTIAL: '部分已采纳', APPLIED: '已采纳' }
const filtered = computed(() => diff.value?.items.filter(item => showUnchanged.value || item.kind !== 'UNCHANGED') ?? [])
const shown = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20))
const hasAccepted = computed(() => diff.value?.items.some(item => item.status === 'ACCEPTED'))
const name = (item: ApiDiffItem) => item.candidate?.name ?? item.before?.name ?? '接口'
const selectable = (item: ApiDiffItem) => item.status === 'PENDING' && ['ADDED', 'REMOVED', 'CHANGED'].includes(item.kind)
const storageKey = (project: string) => `aitest:last-api-diff:${project}`
watch(showUnchanged, () => { page.value = 1 })
watch(() => [visible.value, props.projectId], async () => {
  scope.invalidate(); token = undefined; busy.value = false; error.value = undefined; notice.value = ''; diff.value = undefined
  summaries.value = []; total.value = 0; definitions.value = []; relatedNames.value = {}; selected.value = []; mappings.value = {}; file.value = undefined; importId.value = ''; pendingApply = undefined; healingVisible.value = false; page.value = 1; inputKey.value++
  if (!visible.value || !props.projectId) return
  const current = scope.begin(props.projectId); token = current; busy.value = true
  try {
    const results = await Promise.allSettled([loadHistory(current), loadDefinitions(current)])
    if (!scope.isCurrent(current)) return
    for (const result of results) if (result.status === 'rejected') error.value = result.reason
    let id = ''
    try { id = localStorage.getItem(storageKey(props.projectId)) ?? '' } catch { /* History is also available from the server. */ }
    id ||= summaries.value[0]?.id ?? ''
    if (id) {
      try { receive(await apiDiffApi.get(props.projectId, id, current.signal), current) }
      catch (failure) { if (!(failure instanceof ApiError && failure.status === 404)) throw failure }
    }
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}, { immediate: true })
async function loadHistory(current: ScopeToken, append = false) {
  const result = await apiDiffApi.list(props.projectId, append ? summaries.value.length : 0, current.signal)
  if (scope.isCurrent(current)) { summaries.value = append ? [...summaries.value, ...result.items] : result.items; total.value = result.total }
}
async function loadDefinitions(current: ScopeToken) {
  const project = props.projectId, records: Asset[] = []
  let expected = 1
  while (records.length < expected) {
    const result = await assetApi.list(project, { type: 'API_DEFINITION', offset: records.length, limit: 1000 }, current.signal)
    records.push(...result.items); expected = result.total
    if (!result.items.length) break
  }
  if (scope.isCurrent(current)) definitions.value = records
}
function receive(result: ApiDiff, current: ScopeToken) {
  if (!scope.isCurrent(current) || result.projectId !== props.projectId) return
  diff.value = result; importId.value = result.importId; page.value = 1
  selected.value = selected.value.filter(id => result.items.some(item => item.id === id && selectable(item)))
  mappings.value = Object.fromEntries(result.items.filter(item => item.match === 'EXPLICIT' && item.importKey && item.definitionId).map(item => [item.importKey!, item.definitionId!]))
  try { localStorage.setItem(storageKey(result.projectId), result.id) } catch { /* Server history remains authoritative. */ }
}
async function action(work: (current: ScopeToken) => Promise<void>) {
  const current = token
  if (!current || busy.value) return
  busy.value = true; error.value = undefined; notice.value = ''
  try { await work(current) } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
function chooseFile(value?: File) {
  if (!value || busy.value) return
  file.value = value; diff.value = undefined; selected.value = []; mappings.value = {}; pendingApply = undefined; error.value = undefined; notice.value = ''
  const extension = value.name.split('.').pop()?.toLowerCase()
  if (extension && ['json', 'yaml', 'yml', 'curl', 'har', 'postman', 'xlsx'].includes(extension)) format.value = extension === 'yml' ? 'yaml' : extension
}
function compareUpload() {
  const source = file.value, project = props.projectId, selectedFormat = format.value
  if (!source) return
  void action(async current => {
    const form = new FormData(); form.set('file', source); form.set('type', 'API_DEFINITION'); form.set('format', selectedFormat)
    const preview = await exchangeApi.preview(project, form, current.signal)
    if (!scope.isCurrent(current)) return
    importId.value = preview.id
    if (preview.errors.length || preview.status !== 'READY') throw new Error(preview.errors.map(issue => `${issue.field}：${issue.message}`).join('\n') || '此导入预览不能用于接口对比。')
    const result = await apiDiffApi.preview(project, preview.id, {}, current.signal)
    receive(result, current); await loadHistory(current)
  })
}
function compareExisting() {
  const id = importId.value.trim(), project = props.projectId, chosen = { ...mappings.value }
  if (!id) return
  void action(async current => { receive(await apiDiffApi.preview(project, id, chosen, current.signal), current); await loadHistory(current) })
}
function openHistory(id: string) {
  void action(async current => { pendingApply = undefined; selected.value = []; receive(await apiDiffApi.get(props.projectId, id, current.signal), current) })
}
function refresh() { if (diff.value) openHistory(diff.value.id) }
function toggle(id: string, checked: boolean) { selected.value = checked ? [...new Set([...selected.value, id])] : selected.value.filter(value => value !== id) }
function apply() {
  const target = diff.value, project = props.projectId, itemIds = [...selected.value].sort()
  if (!target || !itemIds.length) return
  if (!pendingApply || pendingApply.diffId !== target.id || JSON.stringify(pendingApply.input.itemIds) !== JSON.stringify(itemIds)) pendingApply = { diffId: target.id, input: { itemIds, idempotencyKey: crypto.randomUUID() } }
  const request = pendingApply
  void action(async current => {
    const result = await apiDiffApi.apply(project, request.diffId, request.input, current.signal)
    if (!scope.isCurrent(current) || diff.value?.id !== request.diffId) return
    pendingApply = undefined; selected.value = []; notice.value = `已采纳 ${itemIds.length} 项接口变更，测试用例保持原状。`
    emit('applied', result.assets, result.removedIds)
    receive(await apiDiffApi.get(project, request.diffId, current.signal), current)
    const results = await Promise.allSettled([loadHistory(current), loadDefinitions(current)])
    if (scope.isCurrent(current)) for (const result of results) if (result.status === 'rejected') error.value = result.reason
  })
}
watch(shown, async items => {
  const current = token, project = props.projectId
  if (!current) return
  const ids = [...new Set(items.flatMap(item => item.affectedAssetIds.slice(0, 30)))].filter(id => !relatedNames.value[id])
  const results = await Promise.allSettled(ids.map(async id => ({ id, asset: await assetApi.get(project, id, current.signal) })))
  if (!scope.isCurrent(current)) return
  for (const result of results) if (result.status === 'fulfilled') relatedNames.value[result.value.id] = result.value.asset.name
})
function healed(assets: Asset[], removedIds: string[]) { emit('applied', assets, removedIds) }
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-drawer v-model:visible="visible" title="接口变更对比" role="dialog" aria-label="接口变更对比" :width="1060" :footer="false" unmount-on-close>
    <p class="small muted">上传新版接口文档，核对定义与受影响资产。先选择接口变更，再根据最新人工内容生成测试修复候选。</p>
    <div class="diff-upload" @dragover.prevent @drop.prevent="chooseFile($event.dataTransfer?.files[0])"><label for="api-diff-file">新版接口文档</label><input :key="inputKey" id="api-diff-file" type="file" aria-label="选择新版接口文档" :disabled="busy" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])"><div class="inline-actions"><a-select v-model="format" :options="['json', 'yaml', 'curl', 'har', 'postman', 'xlsx']" aria-label="接口文档格式" :disabled="busy" style="width: 150px" /><a-button type="primary" :disabled="!file || busy" @click="compareUpload">预检并对比</a-button></div></div>
    <details class="diff-existing"><summary>使用已有导入预览</summary><div class="inline-actions"><a-input v-model="importId" aria-label="已有导入预览 ID" :disabled="busy" placeholder="尚未采纳的 API_DEFINITION 导入 ID" /><a-button :disabled="!importId.trim() || busy" @click="compareExisting">读取并对比</a-button></div></details>
    <div class="diff-history"><a-select :model-value="diff?.id" :options="summaries.map(item => ({ value: item.id, label: `${formatTime(item.createdAt)} · ${item.filename} · ${statusLabels[item.status]}` }))" placeholder="选择历史对比" aria-label="接口对比历史" :disabled="busy" @change="openHistory(String($event))" /><a-button :disabled="busy || !diff" @click="refresh">刷新对比</a-button><a-button v-if="summaries.length < total" :disabled="busy" @click="action(current => loadHistory(current, true))">加载更多对比</a-button></div>
    <ErrorNotice :error="error" style="margin: 16px 0" /><a-alert v-if="notice" type="success" style="margin: 16px 0">{{ notice }}</a-alert>
    <a-spin v-if="busy && !diff" tip="正在核对真实接口定义…" />
    <section v-if="diff" role="region" aria-label="接口差异" class="diff-content">
      <div class="diff-heading"><h3>{{ diff.filename }}</h3><a-tag>{{ statusLabels[diff.status] }}</a-tag><a-checkbox v-model="showUnchanged">显示未变化接口</a-checkbox></div>
      <p class="small muted">比较当前项目的全部接口定义。文件中未出现的接口列为删除候选，只有显式选中才会删除；被测试资产引用的定义会拒绝删除。</p>
      <p v-if="!filtered.length" class="muted">没有需要采纳的业务变化。</p>
      <article v-for="item in shown" :key="item.id" class="diff-card" :data-diff-kind="item.kind" :data-diff-id="item.id">
        <div class="diff-heading"><a-checkbox :model-value="selected.includes(item.id)" :disabled="busy || !selectable(item)" :aria-label="`选择接口变更 ${name(item)}`" @change="toggle(item.id, $event === true)" /><a-tag :color="item.kind === 'REMOVED' ? 'red' : item.kind === 'AMBIGUOUS' ? 'orange' : 'arcoblue'">{{ labels[item.kind] }}</a-tag><strong>{{ name(item) }}</strong><a-tag v-if="item.status === 'ACCEPTED'" color="green">已采纳 v{{ item.appliedVersion }}</a-tag><span v-if="item.before" class="small muted">原定义 v{{ item.before.version }}<template v-if="item.current"> · 当前 v{{ item.current.version }}</template></span></div>
        <p class="small mono">{{ item.candidate?.data.method ?? item.before?.data.method }} {{ item.candidate?.data.path ?? item.before?.data.path }}</p>
        <div v-if="item.candidate && item.status === 'PENDING'" class="diff-mapping"><span class="small">接口身份</span><a-select :model-value="mappings[item.importKey!] ?? ''" :options="[{ value: '', label: item.kind === 'AMBIGUOUS' ? '需要手工选择现有定义' : '使用自动匹配结果' }, ...definitions.map(asset => ({ value: asset.id, label: `${asset.name} · ${asset.data.method} ${asset.data.path}` }))]" allow-search :disabled="busy" :aria-label="`映射 ${name(item)}`" @change="String($event) ? mappings[item.importKey!] = String($event) : delete mappings[item.importKey!]" /><a-button size="small" :disabled="busy" @click="compareExisting">重新对比并应用映射</a-button></div>
        <div v-for="field in item.fields" :key="field.path" class="diff-field"><strong class="small mono">{{ field.path }}</strong><div class="diff-values"><div><span class="small muted">原定义</span><pre>{{ displayValue(field.before) }}</pre></div><div><span class="small muted">导入候选</span><pre>{{ displayValue(field.after) }}</pre></div></div></div>
        <details v-if="item.candidate && !item.fields.length && item.kind !== 'UNCHANGED'"><summary>查看导入候选</summary><pre class="json-view">{{ displayValue(item.candidate.data) }}</pre></details>
        <details v-if="item.affectedAssetIds.length || item.originalAffectedAssetIds.length" class="diff-impacts"><summary>当前受影响 {{ item.affectedAssetIds.length }} 项 · 预览时 {{ item.originalAffectedAssetIds.length }} 项</summary><div class="inline-actions"><a-button v-for="id in item.affectedAssetIds.slice(0, 30)" :key="id" type="text" size="small" @click="emit('openAsset', id)">{{ relatedNames[id] ?? id }}</a-button></div><p v-if="item.affectedAssetIds.length > 30" class="small muted">已显示前 30 个关联入口；完整依赖关系保留在下方。</p><details><summary>依赖关系</summary><pre class="json-view">{{ displayValue(item.links) }}</pre></details></details>
      </article>
      <a-pagination v-if="filtered.length > 20" v-model:current="page" :total="filtered.length" :page-size="20" simple style="margin: 18px 0" />
      <div class="diff-actions"><a-button type="primary" :disabled="!selected.length || busy" :loading="busy" @click="apply">采纳选中接口变更</a-button><a-button :disabled="!hasAccepted || busy" @click="healingVisible = true">AI 定向修复</a-button><span class="small muted">已选 {{ selected.length }} 项 · 用例修复另行预览采纳</span></div>
    </section>
    <GlobalFeedbackDrawer v-model:visible="healingVisible" :project-id="projectId" :api-diff-id="diff?.id" @applied="healed" />
  </a-drawer>
</template>

<style scoped>
.diff-upload { display: grid; gap: 14px; padding: 18px; border: 1px dashed var(--border); border-radius: 10px; background: var(--surface-subtle); margin: 20px 0; }.diff-upload label { font-size: 13px; font-weight: 600; }.diff-upload input { max-width: 100%; }.diff-existing { margin: 16px 0; }.diff-existing .inline-actions { margin-top: 12px; }.diff-existing .arco-input-wrapper { flex: 1; }.diff-history { display: flex; gap: 10px; align-items: center; margin: 20px 0; }.diff-history .arco-select { flex: 1; min-width: 0; }.diff-heading { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }.diff-heading h3 { margin: 0; }.diff-card { padding: 18px; border: 1px solid var(--border); border-radius: 10px; margin-top: 16px; min-width: 0; }.diff-mapping { display: flex; align-items: center; gap: 12px; margin: 14px 0; }.diff-mapping .arco-select { flex: 1; min-width: 0; }.diff-field { margin: 14px 0; overflow-wrap: anywhere; }.diff-values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 8px; }.diff-values > div { background: var(--surface-muted); padding: 12px; border-radius: 6px; min-width: 0; }.diff-values > div:last-child { background: var(--success-soft); }.diff-values pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 240px; overflow: auto; font-size: 12px; margin: 8px 0 0; }.diff-impacts { margin-top: 18px; font-size: 12px; }.diff-impacts .inline-actions { flex-wrap: wrap; }.diff-actions { position: sticky; bottom: -16px; padding: 18px 0; display: flex; flex-wrap: wrap; align-items: center; gap: 12px; background: var(--color-bg-2); border-top: 1px solid var(--border); margin-top: 24px; }
.diff-mapping > span { flex-shrink: 0; }
@media (max-width: 600px) { .diff-values { grid-template-columns: minmax(0, 1fr); }.diff-history, .diff-mapping { flex-wrap: wrap; }.diff-history .arco-select, .diff-mapping .arco-select { flex-basis: 100%; }.diff-card { padding: 12px; } }
</style>
