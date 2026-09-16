<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { exchangeApi, type ExchangeCapability, type ImportPreview, type TemplateFamily } from '../../api/exchange'
import { assetApi } from '../../api/assets'
import { http, isRecord, saveDownload } from '../../api/client'
import type { AssetType } from '../../api/types'
import { useWorkspaceStore } from '../../stores/workspace'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import AppTabs from '../common/AppTabs.vue'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ projectId: string; type: AssetType; initialMode: 'import' | 'export' | 'templates'; parentId?: string; parents?: { label: string; value: string }[]; selectedIds?: string[] }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ imported: [ids: string[]] }>()
const workspace = useWorkspaceStore()
const mode = ref('import')
const capabilities = ref<ExchangeCapability[]>([])
const templates = ref<TemplateFamily[]>([])
const format = ref('json')
const exportFormat = ref('json')
const exportScope = ref('ALL')
const selectedParentId = ref('')
const file = ref<File>()
const inputKey = ref(0)
const preview = ref<ImportPreview>()
const previewDirty = ref(false)
const previewPage = ref(1)
const resumeId = ref('')
const mappings = ref<Record<string, string>>({})
const columnMappings = ref<Record<string, string>>({})
const columnTypes = ref<Record<string, string>>({})
const originalColumns = ref<string[]>([])
const referenceOptions = ref<Record<string, { label: string; value: string }[]>>({})
const busy = ref(false)
const loading = ref(false)
const error = ref<unknown>()
const scope = new RequestScope()
let token: ScopeToken | undefined
const capability = computed(() => capabilities.value.find((entry) => entry.type === props.type))
const importFormats = computed(() => capability.value?.importFormats ?? [])
const exportFormats = computed(() => capability.value?.exportFormats ?? [])
const shownNodes = computed(() => preview.value?.nodes.slice((previewPage.value - 1) * 40, previewPage.value * 40) ?? [])
const nodeNames = computed(() => new Map(preview.value?.nodes.map((node) => [node.key, node.name])))
const mappingCapabilities = computed(() => {
  const capabilities = preview.value?.metadata.mappingCapabilities
  return { renameColumns: isRecord(capabilities) && capabilities.renameColumns === true, convertTypes: isRecord(capabilities) && capabilities.convertTypes === true }
})
const external = computed(() => {
  const refs = preview.value?.metadata.externalReferences
  return isRecord(refs) ? Object.entries(refs).flatMap(([key, value]) => isRecord(value) && typeof value.type === 'string' ? [{ key, type: value.type as AssetType, name: String(value.name ?? key) }] : []) : []
})
const templateRows = computed(() => templates.value.flatMap((family) => family.variants.map((variant) => ({ family: family.family, label: family.label, version: family.version, ...variant }))).sort((a, b) => Number(b.type === props.type) - Number(a.type === props.type)))
const lastKey = () => `aitest:last-import:${props.projectId}:${props.type}`
function nonEmptyMappings(value: unknown): Record<string, string> {
  return isRecord(value) ? Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string' && !!entry[1].trim()).map(([key, text]) => [key, text.trim()])) : {}
}

watch(() => [visible.value, props.projectId, props.type, props.initialMode], async () => {
  scope.invalidate(); token = undefined
  busy.value = false; loading.value = false; error.value = undefined
  preview.value = undefined; file.value = undefined; mappings.value = {}; columnMappings.value = {}; columnTypes.value = {}; originalColumns.value = []; referenceOptions.value = {}
  previewDirty.value = false; previewPage.value = 1; inputKey.value++; selectedParentId.value = props.parentId ?? ''; mode.value = props.initialMode
  exportScope.value = props.selectedIds?.length ? 'SELECTED' : 'ALL'
  try { resumeId.value = localStorage.getItem(lastKey()) ?? '' } catch { resumeId.value = '' }
  if (!visible.value) return
  const current = scope.begin(`${props.projectId}:${props.type}`); token = current; loading.value = true
  const results = await Promise.allSettled([exchangeApi.capabilities(current.signal), exchangeApi.templates(current.signal)])
  if (!scope.isCurrent(current)) return
  if (results[0].status === 'fulfilled') capabilities.value = results[0].value
  else error.value = results[0].reason
  if (results[1].status === 'fulfilled') templates.value = results[1].value
  else error.value = results[1].reason
  format.value = importFormats.value.includes('json') ? 'json' : importFormats.value[0] ?? ''
  exportFormat.value = exportFormats.value.includes('json') ? 'json' : exportFormats.value[0] ?? ''
  loading.value = false
}, { immediate: true })

function chooseFile(value?: File) {
  if (!value || busy.value || loading.value) return
  file.value = value; preview.value = undefined; mappings.value = {}; columnMappings.value = {}; columnTypes.value = {}; originalColumns.value = []; previewDirty.value = false; error.value = undefined
  let extension = value.name.split('.').pop()?.toLowerCase() ?? ''
  if (extension === 'yml') extension = 'yaml'
  if (importFormats.value.includes(extension)) format.value = extension
}
async function receivePreview(result: ImportPreview, current: ScopeToken) {
  if (!scope.isCurrent(current) || result.projectId !== props.projectId) return
  preview.value = result; resumeId.value = result.id; previewDirty.value = false; previewPage.value = 1
  mappings.value = nonEmptyMappings(result.metadata.referenceMappings)
  columnMappings.value = mappingCapabilities.value.renameColumns ? nonEmptyMappings(result.metadata.columnMappings) : {}
  columnTypes.value = mappingCapabilities.value.convertTypes ? nonEmptyMappings(result.metadata.columnTypes) : {}
  originalColumns.value = []
  try { localStorage.setItem(lastKey(), result.id) } catch { /* The server preview remains reopenable by ID. */ }
  const columns = result.metadata.originalColumns ?? result.nodes.find((node) => node.type === 'DATASET')?.data.columns
  if (Array.isArray(columns)) {
    const originalByMapped = new Map(Object.entries(columnMappings.value).map(([original, mapped]) => [mapped, original]))
    originalColumns.value = [...new Set(columns.map(column => result.metadata.originalColumns ? String(column) : originalByMapped.get(String(column)) ?? String(column)))]
  }
  const types = [...new Set(external.value.map((ref) => ref.type))]
  const options = await Promise.allSettled(types.map(async (type) => {
    const items: { label: string; value: string }[] = []; let offset = 0
    while (true) {
      const page = await assetApi.list(result.projectId, { type, offset, limit: 100 }, current.signal)
      items.push(...page.items.map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.id.slice(0, 8)}` })))
      offset += page.items.length
      if (!page.items.length || offset >= page.total) break
    }
    return { type, items }
  }))
  if (!scope.isCurrent(current)) return
  for (const entry of options) { if (entry.status === 'fulfilled') referenceOptions.value[entry.value.type] = entry.value.items; else error.value = entry.reason }
}
async function reopen() {
  const current = token
  if (!current || !resumeId.value.trim() || busy.value) return
  busy.value = true; error.value = undefined
  try {
    const result = await exchangeApi.get(props.projectId, resumeId.value.trim(), current.signal)
    if (!scope.isCurrent(current)) return
    if (result.type !== props.type) throw new Error(`该预览属于${workspace.label(result.type)}，请切换到对应资产类型后打开。`)
    file.value = undefined; inputKey.value++
    format.value = result.format; selectedParentId.value = result.parentId ?? ''
    await receivePreview(result, current)
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function inspect() {
  const current = token
  if (!current || busy.value || (!file.value && !preview.value)) return
  busy.value = true; error.value = undefined
  try {
    let source = file.value
    if (!source && preview.value) {
      const saved = await http.download(`/projects/${encodeURIComponent(props.projectId)}/files/${encodeURIComponent(preview.value.fileId)}`, { signal: current.signal })
      if (!scope.isCurrent(current)) return
      source = new File([saved.blob], preview.value.filename, { type: saved.blob.type })
    }
    if (!source) return
    const form = new FormData(); form.append('file', source); form.append('type', props.type); form.append('format', format.value)
    if (selectedParentId.value) form.append('parentId', selectedParentId.value)
    form.append('referenceMappings', JSON.stringify(nonEmptyMappings(mappings.value))); form.append('columnMappings', JSON.stringify(nonEmptyMappings(columnMappings.value))); form.append('columnTypes', JSON.stringify(nonEmptyMappings(columnTypes.value)))
    await receivePreview(await exchangeApi.preview(props.projectId, form, current.signal), current)
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function apply() {
  const current = token, target = preview.value
  if (!current || !target || target.status !== 'READY' || previewDirty.value || busy.value) return
  busy.value = true; error.value = undefined
  try {
    const result = await exchangeApi.apply(target.projectId, target.id, current.signal)
    if (!scope.isCurrent(current) || preview.value?.id !== target.id) return
    preview.value = { ...target, status: 'APPLIED', result }
    Message.success(`已导入 ${result.createdCount} 条资产`); emit('imported', result.assetIds)
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function downloadExport() {
  const current = token
  if (!current || busy.value) return
  busy.value = true; error.value = undefined
  try {
    const result = await exchangeApi.export(props.projectId, { type: props.type, format: exportFormat.value, ...(exportScope.value === 'SELECTED' ? { assetIds: props.selectedIds } : {}) }, current.signal)
    if (scope.isCurrent(current)) { saveDownload(result); Message.success(`已下载 ${result.filename}`) }
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
async function downloadTemplate(family: string, kind: string) {
  const current = token
  if (!current || busy.value) return
  busy.value = true; error.value = undefined
  try { const result = await exchangeApi.template(family, kind, current.signal); if (scope.isCurrent(current)) saveDownload(result) }
  catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally { if (scope.isCurrent(current)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-drawer v-model:visible="visible" :width="880" :footer="false" :title="`${workspace.label(type)} · 导入与导出`" role="dialog" aria-label="导入与导出" unmount-on-close>
    <AppTabs v-model="mode" :items="[{ value: 'import', label: '导入资产' }, { value: 'export', label: '导出资产' }, { value: 'templates', label: '标准模板' }]" label="文件交换方式" />
    <ErrorNotice :error="error" style="margin: 16px 0" />
    <a-spin v-if="loading" tip="读取支持的格式…" />
    <template v-else-if="mode === 'import'">
      <p class="muted small">先检查文件内容与关联关系，确认后保存。已有资产可继续手工编辑。</p>
      <div class="exchange-drop" @dragover.prevent @drop.prevent="chooseFile($event.dataTransfer?.files[0])">
        <label>拖入文件或选择本地文件<input :key="inputKey" type="file" aria-label="选择导入文件" :disabled="busy" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])"></label>
        <p v-if="file" class="small">{{ file.name }} · {{ Math.ceil(file.size / 1024) }} KB</p>
      </div>
      <div class="exchange-fields"><label>文件格式<a-select v-model="format" :options="importFormats" :disabled="busy" aria-label="导入文件格式" @change="previewDirty = true" /></label><label>导入位置<a-select v-model="selectedParentId" :options="[{ label: '按文件层级导入根节点', value: '' }, ...(parents ?? [])]" :disabled="busy" aria-label="导入位置" allow-search @change="previewDirty = true" /></label></div>
      <a-button type="primary" :disabled="(!file && !preview) || !format || !projectId" :loading="busy" @click="inspect">{{ preview ? '重新检查映射与文件' : '预览并检查' }}</a-button>
      <details class="exchange-resume"><summary>继续已有导入预览</summary><div class="inline-actions"><a-input v-model="resumeId" placeholder="导入预览 ID" :input-attrs="{ 'aria-label': '导入预览 ID' }" /><a-button :disabled="!resumeId.trim() || busy" @click="reopen">打开预览</a-button></div></details>
      <template v-if="preview">
        <div class="exchange-status"><strong>{{ preview.filename }}</strong><a-tag :color="preview.status === 'INVALID' ? 'red' : preview.status === 'APPLIED' ? 'green' : 'arcoblue'">{{ { INVALID: '需要修正', READY: '可以导入', APPLIED: '已导入' }[preview.status] }}</a-tag><span>{{ preview.nodes.length }} 条资产</span></div>
        <p class="small muted">预览 ID：{{ preview.id }}</p>
        <div v-if="external.length" class="exchange-mapping"><h3>绑定当前项目资源</h3><label v-for="reference in external" :key="reference.key">{{ reference.name }} · {{ workspace.label(reference.type) }}<a-select v-model="mappings[reference.key]" :options="referenceOptions[reference.type] ?? []" :disabled="busy || preview.status === 'APPLIED'" :aria-label="`映射 ${reference.name}`" allow-search placeholder="选择已有资源" @change="previewDirty = true" /></label></div>
        <details v-if="originalColumns.length && (mappingCapabilities.renameColumns || mappingCapabilities.convertTypes)" class="exchange-columns"><summary>{{ mappingCapabilities.convertTypes ? '数据列名称与类型' : '数据列名称' }}</summary><p v-if="!mappingCapabilities.convertTypes" class="small muted">此文件保留每个值的原始类型，可以调整列名。</p><div v-for="column in originalColumns" :key="column" class="exchange-fields"><label v-if="mappingCapabilities.renameColumns">{{ column }}<a-input v-model="columnMappings[column]" :placeholder="column" :aria-label="`列 ${column} 的映射名称`" :disabled="busy || preview.status === 'APPLIED'" @input="previewDirty = true" /></label><label v-if="mappingCapabilities.convertTypes">类型<a-select v-model="columnTypes[column]" :options="['STRING', 'NUMBER', 'INTEGER', 'BOOLEAN', 'DATE', 'JSON', 'NULL']" :aria-label="`列 ${column} 的类型`" :disabled="busy || preview.status === 'APPLIED'" placeholder="按原始值" allow-clear @change="previewDirty = true" /></label></div></details>
        <a-alert v-if="previewDirty" type="warning" style="margin-block: 12px">配置有变化，请重新检查后再导入。</a-alert>
        <div v-if="preview.errors.length || preview.warnings.length" class="exchange-issues"><p v-for="(issue, index) in preview.errors" :key="`error-${index}`" class="error-line">{{ issue.source }} · 第 {{ issue.row }} 行 · {{ issue.field }}：{{ issue.message }}</p><p v-for="(issue, index) in preview.warnings" :key="`warn-${index}`" class="muted">{{ issue.source }} · {{ issue.field }}：{{ issue.message }}</p></div>
        <div class="table-overflow surface"><table class="asset-table" aria-label="导入预览内容"><thead><tr><th>名称</th><th>类型</th><th>所属节点</th><th>内容</th></tr></thead><tbody><tr v-for="node in shownNodes" :key="node.key"><td>{{ node.name }}</td><td>{{ workspace.label(node.type) }}</td><td>{{ node.parentKey ? nodeNames.get(node.parentKey) ?? node.parentKey : '根节点' }}</td><td><details><summary>查看字段</summary><pre class="json-view">{{ JSON.stringify(node.data, null, 2) }}</pre></details></td></tr></tbody></table></div>
        <a-pagination v-if="preview.nodes.length > 40" v-model:current="previewPage" :total="preview.nodes.length" :page-size="40" simple style="margin-block: 12px" />
        <div class="exchange-apply"><a-alert v-if="preview.status === 'APPLIED'" type="success">已保存 {{ preview.result?.createdCount }} 条资产。重复打开此预览不会再次创建。</a-alert><a-button v-else type="primary" :disabled="preview.status !== 'READY' || previewDirty" :loading="busy" @click="apply">确认导入 {{ preview.nodes.length }} 条资产</a-button></div>
      </template>
    </template>
    <template v-else-if="mode === 'export'">
      <p>导出当前项目的{{ workspace.label(type) }}，包含所选记录的子项与必要引用。</p>
      <div class="exchange-fields"><label>导出范围<a-select v-model="exportScope" :options="[{ label: `当前项目全部${workspace.label(type)}`, value: 'ALL' }, ...(selectedIds?.length ? [{ label: `仅已选 ${selectedIds.length} 条`, value: 'SELECTED' }] : [])]" aria-label="导出范围" /></label><label>文件格式<a-select v-model="exportFormat" :options="exportFormats" aria-label="导出文件格式" /></label></div>
      <a-button type="primary" :disabled="!exportFormat || !projectId || (exportScope === 'SELECTED' && !selectedIds?.length)" :loading="busy" @click="downloadExport">下载导出文件</a-button>
    </template>
    <template v-else>
      <p class="muted small">模板包含字段说明和可直接导入的示例，按当前资产类型优先排列。</p>
      <article v-for="entry in templateRows" :key="`${entry.family}:${entry.format}`" class="exchange-template"><div><strong>{{ entry.label }} · {{ entry.format.toUpperCase() }}</strong><p class="small muted">{{ entry.instructions }}</p><span class="small">{{ entry.filename }} · {{ entry.version }}</span></div><a-button :disabled="busy" :aria-label="`下载 ${entry.label} ${entry.format} 模板`" @click="downloadTemplate(entry.family, entry.format)">下载</a-button></article>
    </template>
  </a-drawer>
</template>

<style scoped>
.exchange-drop { border: 1px dashed var(--border-strong); border-radius: 10px; padding: 24px; background: var(--surface-subtle); margin: 18px 0; }
.exchange-drop label { display: grid; gap: 14px; color: var(--muted); }
.exchange-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin: 18px 0; }
.exchange-fields label, .exchange-mapping label { display: grid; gap: 8px; font-size: 13px; }
.asset-table { min-width: 620px; }
.exchange-resume { margin: 20px 0; } .exchange-resume .inline-actions { margin-top: 12px; }
.exchange-status { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 24px; }
.exchange-mapping { padding: 16px; background: var(--primary-soft); border-radius: 8px; display: grid; gap: 16px; margin-block: 16px; }
.exchange-mapping h3 { margin: 0; font-size: 14px; }
.exchange-issues { font-size: 12px; line-height: 1.7; max-height: 220px; overflow: auto; margin-block: 16px; }
.error-line { color: var(--danger); } .exchange-apply { margin-block: 20px; }
.exchange-template { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 20px 0; border-bottom: 1px solid var(--border); }
.exchange-template p { line-height: 1.7; max-width: 580px; }
@media(max-width: 600px) { .exchange-fields { grid-template-columns: 1fr; } }
</style>
