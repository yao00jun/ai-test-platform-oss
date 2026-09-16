<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { allAssets, assetApi } from '../../api/assets'
import { sourceStatusLabels, type SourceAnalysis } from '../../api/analysis'
import { ApiError } from '../../api/client'
import { exchangeApi, type ImportPreview } from '../../api/exchange'
import { pipelineApi, type PipelineAccepted, type PipelineInput, type PipelineOptions } from '../../api/pipelines'
import type { Asset } from '../../api/types'
import { beginPreparation, completePreparation, emptyPipelineSource, inputHash, updatePreparation, type PipelinePreparation } from '../../core/pipeline-preparation'
import { preparePipelineSource } from '../../core/prepare-pipeline-source'
import { sourceSettings } from '../../core/source-analysis-state'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import PipelineConfigFields from './PipelineConfigFields.vue'
import PipelineSourceFields from './PipelineSourceFields.vue'

const props = defineProps<{ projectId: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ accepted: [result: PipelineAccepted, projectId: string] }>()
const requirements = ref<Asset[]>([]), definitions = ref<Asset[]>([]), environments = ref<Asset[]>([]), databases = ref<Asset[]>([]), recordings = ref<Asset[]>([])
const requirementMode = ref('paste'), apiMode = ref('paste'), requirementName = ref(''), requirementText = ref(''), requirementPath = ref(''), apiText = ref(''), apiFormat = ref('auto')
const requirementFile = ref<File>(), apiFile = ref<File>(), requirementIds = ref<string[]>([]), apiIds = ref<string[]>([])
const options = ref<PipelineOptions>({ environmentId: '', databaseSourceIds: [], uiEvidenceIds: [], execute: true })
const draft = ref<PipelinePreparation>(), preview = ref<ImportPreview>()
const source = ref(emptyPipelineSource()), sourceStatus = ref<SourceAnalysis>()
const loading = ref(false), busy = ref(false), progress = ref(''), error = ref<unknown>(), catalogError = ref<unknown>()
const scope = new RequestScope(); let current: ScopeToken | undefined
const frozen = computed(() => busy.value || !!draft.value?.request)

watch(() => [visible.value, props.projectId], () => {
  scope.invalidate(); current = undefined; loading.value = false; busy.value = false; error.value = undefined; catalogError.value = undefined; progress.value = ''; preview.value = undefined
  sourceStatus.value = undefined
  requirements.value = []; definitions.value = []; environments.value = []; databases.value = []; recordings.value = []
  requirementName.value = ''; requirementText.value = ''; requirementPath.value = ''; apiText.value = ''; requirementFile.value = undefined; apiFile.value = undefined; apiFormat.value = 'auto'
  requirementIds.value = []; apiIds.value = []; options.value = { environmentId: '', databaseSourceIds: [], uiEvidenceIds: [], execute: true }
  if (!visible.value || !props.projectId) return
  current = scope.begin(props.projectId); restore(); void loadCatalog(current)
}, { immediate: true })
function restore(fresh = false) {
  draft.value = beginPreparation(props.projectId, fresh)
  source.value = draft.value.source ? JSON.parse(JSON.stringify(draft.value.source)) : emptyPipelineSource()
  requirementMode.value = draft.value.requirementIds?.length ? 'saved' : 'paste'
  apiMode.value = draft.value.apiDefinitionIds ? 'saved' : 'paste'
  const savedOptions = draft.value.request ?? draft.value.options
  if (savedOptions) options.value = { environmentId: savedOptions.environmentId, databaseSourceIds: [...savedOptions.databaseSourceIds], uiEvidenceIds: [...savedOptions.uiEvidenceIds], execute: savedOptions.execute }
}
async function loadCatalog(token: ScopeToken) {
  loading.value = true; catalogError.value = undefined
  const [catalogs, project] = await Promise.allSettled([
    Promise.allSettled(['REQUIREMENT', 'API_DEFINITION', 'ENVIRONMENT', 'DATABASE_SOURCE', 'UI_SCENARIO'].map(type => allAssets(props.projectId, type as Asset['type'], token.signal))),
    assetApi.get(props.projectId, props.projectId, token.signal),
  ])
  if (!scope.isCurrent(token)) return
  const targets = [requirements, definitions, environments, databases, recordings]
  if (catalogs.status === 'fulfilled') catalogs.value.forEach((result, index) => { if (result.status === 'fulfilled') targets[index]!.value = result.value; else catalogError.value = result.reason })
  else catalogError.value = catalogs.reason
  if (project.status === 'fulfilled' && !draft.value?.source && source.value.mode === 'none') Object.assign(source.value.fields, sourceSettings(project.value))
  else if (project.status === 'rejected') catalogError.value = project.reason
  if (!draft.value?.request && !draft.value?.options) {
    options.value.environmentId = environments.value.length === 1 ? environments.value[0]!.id : ''
    options.value.databaseSourceIds = databases.value.filter(asset => !asset.data.environmentId || asset.data.environmentId === options.value.environmentId).map(asset => asset.id)
  }
  loading.value = false
}
function chooseFile(event: Event, target: 'requirement' | 'api') {
  if (frozen.value) return
  const file = (event.target as HTMLInputElement).files?.[0]
  if (target === 'requirement') requirementFile.value = file; else apiFile.value = file
}
function drop(event: DragEvent, target: 'requirement' | 'api') {
  if (frozen.value) return
  const file = event.dataTransfer?.files[0]
  if (!file) return
  if (target === 'requirement') { requirementMode.value = 'upload'; requirementFile.value = file }
  else { apiMode.value = 'upload'; apiFile.value = file }
}
function reset() { if (busy.value) return; restore(true); error.value = undefined; preview.value = undefined; progress.value = '' }

async function submit() {
  const token = current, initial = draft.value
  if (!token || !initial || busy.value || loading.value) return
  const project = props.projectId, config = { environmentId: options.value.environmentId, databaseSourceIds: [...options.value.databaseSourceIds], uiEvidenceIds: [...options.value.uiEvidenceIds], execute: options.value.execute }
  const input = { requirementMode: requirementMode.value, apiMode: apiMode.value, name: requirementName.value.trim(), text: requirementText.value, path: requirementPath.value.trim(), requirementFile: requirementFile.value, apiFile: apiFile.value, apiText: apiText.value, apiFormat: apiFormat.value, requirementIds: [...requirementIds.value], apiIds: [...apiIds.value] }
  const sourceInput = JSON.parse(JSON.stringify(source.value)) as typeof source.value
  let prepared = initial
  const persist = (patch: Partial<PipelinePreparation>) => {
    const next = updatePreparation(project, initial.id, patch)
    if (!next) throw new Error('本次准备记录已被替换，请重新打开流水线向导。')
    prepared = next; if (scope.isCurrent(token)) draft.value = next
  }
  busy.value = true; error.value = undefined
  try {
    if (!prepared.request) {
      persist({ options: config, source: sourceInput })
      if (input.requirementMode === 'paste' && !input.text.trim() || input.requirementMode === 'path' && !input.path || input.requirementMode === 'upload' && !input.requirementFile || input.requirementMode === 'existing' && !input.requirementIds.length) throw new Error('请提供 BA 需求正文、文件、绝对路径或已保存需求。')
      if (input.apiMode === 'paste' && !input.apiText.trim() || input.apiMode === 'upload' && !input.apiFile || input.apiMode === 'existing' && !input.apiIds.length) throw new Error('请提供开发接口文档，或明确选择稍后补充。')
      progress.value = '正在准备 BA 需求…'
      let selectedRequirements: string[]
      if (input.requirementMode === 'saved') selectedRequirements = prepared.requirementIds ?? []
      else if (input.requirementMode === 'existing') { selectedRequirements = input.requirementIds; persist({ requirementIds: selectedRequirements, requirementHash: undefined, requirementKey: undefined }) }
      else {
        const hash = await inputHash(JSON.stringify(input.requirementMode === 'upload' ? { mode: input.requirementMode, name: input.requirementFile!.name, digest: await inputHash(input.requirementFile!) } : { mode: input.requirementMode, name: input.name, text: input.requirementMode === 'paste' ? input.text : '', path: input.requirementMode === 'path' ? input.path : '' }))
        if (prepared.requirementHash !== hash) persist({ requirementHash: hash, requirementKey: crypto.randomUUID(), requirementIds: undefined })
        if (!prepared.requirementIds?.length) {
          const requirement = input.requirementMode === 'upload' ? await pipelineApi.uploadDocument(project, input.requirementFile!, prepared.requirementKey!) : await pipelineApi.readDocument(project, { ...(input.requirementMode === 'path' ? { path: input.path } : { text: input.text, name: input.name }), idempotencyKey: prepared.requirementKey! })
          persist({ requirementIds: [requirement.id] })
        }
        selectedRequirements = prepared.requirementIds!
      }
      if (!scope.isCurrent(token)) return
      progress.value = '正在准备开发接口文档…'
      let selectedDefinitions: string[]
      if (input.apiMode === 'saved') selectedDefinitions = prepared.apiDefinitionIds ?? []
      else if (input.apiMode === 'existing' || input.apiMode === 'none') { selectedDefinitions = input.apiMode === 'none' ? [] : input.apiIds; persist({ apiDefinitionIds: selectedDefinitions, apiHash: undefined, importId: undefined }) }
      else {
        const raw = input.apiText.trimStart()
        const detected = /^curl(?:\s|$)/.test(raw) ? 'curl' : raw.startsWith('{') || raw.startsWith('[') ? 'json' : 'yaml'
        const format = input.apiFormat === 'auto' ? input.apiMode === 'paste' ? detected : undefined : input.apiFormat
        const file = input.apiMode === 'upload' ? input.apiFile! : new File([input.apiText], `开发接口.${format === 'curl' ? 'curl' : format?.endsWith('yaml') ? 'yaml' : 'json'}`, { type: 'text/plain' })
        const hash = await inputHash(JSON.stringify({ name: file.name, format, digest: await inputHash(file) }))
        if (prepared.apiHash !== hash) persist({ apiHash: hash, importId: undefined, apiDefinitionIds: undefined })
        if (!prepared.apiDefinitionIds) {
          let inspected: ImportPreview
          if (prepared.importId) inspected = await exchangeApi.get(project, prepared.importId, token.signal)
          else {
            const form = new FormData(); form.append('file', file); form.append('type', 'API_DEFINITION'); if (format) form.append('format', format)
            inspected = await exchangeApi.preview(project, form)
            persist({ importId: inspected.id })
          }
          if (!scope.isCurrent(token)) return
          preview.value = inspected
          if (inspected.status === 'INVALID') throw new Error('开发接口文档预检未通过，请修正下方问题后重试。')
          if (!inspected.nodes.length || inspected.nodes.some(node => node.type !== 'API_DEFINITION')) throw new Error('此入口接受开发接口定义；其他测试资产包请使用对应模块的导入功能。')
          const applied = inspected.status === 'APPLIED' && inspected.result ? inspected.result : await exchangeApi.apply(project, inspected.id)
          persist({ apiDefinitionIds: inspected.nodes.map(node => applied.keyToId[node.key]).filter((id): id is string => !!id) })
        }
        selectedDefinitions = prepared.apiDefinitionIds!
      }
      if (!scope.isCurrent(token)) return
      progress.value = sourceInput.mode === 'none' ? '输入已准备完成…' : '正在采集并核对固定源码依据…'
      const sourceSnapshotId = await preparePipelineSource(project, prepared.source!, next => { persist({ source: next }); if (scope.isCurrent(token)) source.value = next }, token.signal, snapshot => {
        if (scope.isCurrent(token)) { sourceStatus.value = snapshot; progress.value = `${sourceStatusLabels[snapshot.status] ?? snapshot.status} · ${snapshot.fileCount} 个文件` }
      })
      if (!scope.isCurrent(token)) return
      const request: PipelineInput = { projectId: project, requirementIds: selectedRequirements, apiDefinitionIds: selectedDefinitions, ...config, ...(sourceSnapshotId ? { sourceSnapshotId } : {}), idempotencyKey: crypto.randomUUID() }
      persist({ request })
    }
    progress.value = '正在提交全自动流水线…'
    const accepted = await pipelineApi.submit(prepared.request!)
    completePreparation(project, initial.id)
    if (!scope.isCurrent(token)) return
    visible.value = false; emit('accepted', accepted, project)
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    if (prepared.request && failure instanceof ApiError && failure.status >= 400 && failure.status < 500) persist({ request: undefined })
    error.value = failure
  } finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-drawer v-model:visible="visible" title="新建全自动测试" role="dialog" aria-label="新建全自动测试" :width="860" :footer="false" unmount-on-close>
    <p class="pipeline-intro">提供 BA 需求与接口文档，可同时接入后端源码、前端源码和 DDL。自动分析需求、生成测试资产并编排执行；每项结果都可以继续编辑或局部 AI 调优。</p>
    <ErrorNotice :error="error" /><ErrorNotice :error="catalogError" />
    <a-alert v-if="draft?.request" type="warning" class="preparation-notice">上次提交尚未确认结果。继续提交会恢复同一条流水线；已经准备的需求和接口保留。</a-alert>
    <a-alert v-else-if="draft?.requirementIds?.length || draft?.apiDefinitionIds" class="preparation-notice">已恢复上次准备的输入，可直接继续。选择新的输入时，只重新准备对应部分。</a-alert>
    <div class="pipeline-source" @dragover.prevent @drop.prevent="drop($event, 'requirement')">
      <h3>1. BA 原始需求</h3>
      <label>需求来源<select v-model="requirementMode" aria-label="需求来源" :disabled="frozen"><option v-if="draft?.requirementIds?.length" value="saved">使用已准备需求（{{ draft.requirementIds.length }} 份）</option><option value="paste">粘贴正文</option><option value="path">读取本地绝对路径</option><option value="upload">上传或拖入文件</option><option value="existing">选择已保存需求</option></select></label>
      <template v-if="requirementMode === 'paste'"><label>需求名称<input v-model="requirementName" aria-label="需求名称" :disabled="frozen" placeholder="例如：订单结算需求"></label><label>BA 原始需求正文<textarea v-model="requirementText" aria-label="BA 原始需求正文" :disabled="frozen" rows="7" placeholder="粘贴 BA 原始需求、业务规则与验收条件" /></label></template>
      <template v-else-if="requirementMode === 'path'"><label>本地绝对路径<input v-model="requirementPath" aria-label="BA 文档绝对路径" :disabled="frozen" placeholder="D:\PRD.docx"></label><p class="small muted">路径由后端所在电脑读取，支持已配置的可读目录。</p></template>
      <template v-else-if="requirementMode === 'upload'"><label class="pipeline-file">选择 BA 文档<input type="file" aria-label="上传 BA 文档" accept=".doc,.docx,.pdf,.xls,.xlsx,.md,.txt,.csv" :disabled="frozen" @change="chooseFile($event, 'requirement')"></label><p class="small muted">{{ requirementFile?.name ?? '可拖入 Word、PDF、Excel 或 Markdown，最大 32 MB。' }}</p></template>
      <label v-else-if="requirementMode === 'existing'">已保存需求<select v-model="requirementIds" multiple aria-label="已保存需求" :disabled="frozen || loading" size="4"><option v-for="asset in requirements" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label>
    </div>
    <div class="pipeline-source" @dragover.prevent @drop.prevent="drop($event, 'api')">
      <h3>2. 开发接口文档</h3>
      <label>接口来源<select v-model="apiMode" aria-label="接口来源" :disabled="frozen"><option v-if="draft?.apiDefinitionIds" value="saved">使用已准备接口（{{ draft.apiDefinitionIds.length }} 个）</option><option value="paste">粘贴接口文档</option><option value="upload">上传或拖入接口文件</option><option value="existing">选择已保存接口</option><option value="none">稍后补充</option></select></label>
      <template v-if="apiMode === 'paste' || apiMode === 'upload'"><label>接口格式<select v-model="apiFormat" aria-label="接口文档格式" :disabled="frozen"><option value="auto">自动识别</option><option value="openapi-json">Swagger / OpenAPI JSON</option><option value="openapi-yaml">OpenAPI YAML</option><option value="curl">cURL</option><option value="har">HAR</option><option value="postman">Postman</option></select></label><label v-if="apiMode === 'paste'">开发接口文档内容<textarea v-model="apiText" aria-label="开发接口文档内容" :disabled="frozen" rows="7" placeholder="粘贴 Swagger / OpenAPI、cURL 或 HAR" /></label><template v-else><label class="pipeline-file">选择接口文件<input type="file" aria-label="上传开发接口文档" accept=".json,.yaml,.yml,.har,.curl,.txt" :disabled="frozen" @change="chooseFile($event, 'api')"></label><p class="small muted">{{ apiFile?.name ?? '支持 Swagger / OpenAPI、cURL、HAR 和 Postman。' }}</p></template></template>
      <label v-else-if="apiMode === 'existing'">已保存接口<select v-model="apiIds" aria-label="已保存接口" multiple :disabled="frozen || loading" size="4"><option v-for="asset in definitions" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label>
      <p v-if="apiMode === 'none'" class="small muted">接口与场景阶段会明确保留待补充项；导入接口后可从该阶段恢复。</p>
      <ul v-if="preview?.errors.length" class="preview-issues"><li v-for="(issue, index) in preview.errors" :key="index">{{ issue.field }} · 第 {{ issue.row }} 行：{{ issue.message }}</li></ul>
    </div>
    <PipelineSourceFields v-model="source" :project-id="projectId" :disabled="frozen || loading" :failed="!!sourceStatus && ['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(sourceStatus.status)" />
    <a-alert v-if="sourceStatus?.status === 'PARTIAL'" type="warning">源码分析包含缺口，已保留诊断与可用证据。可以在项目源码分析中查看具体文件。</a-alert>
    <details class="pipeline-options"><summary>执行与证据配置</summary><PipelineConfigFields v-model="options" :environments="environments" :databases="databases" :recordings="recordings" :disabled="frozen || loading" /></details>
    <p v-if="progress" role="status" class="small muted">{{ progress }}</p>
    <div class="pipeline-wizard-actions"><a-button :disabled="busy" @click="reset">清除本次准备记录</a-button><a-button type="primary" :loading="busy" :disabled="loading" @click="submit">{{ draft?.request ? '恢复上次提交' : '开始生成测试资产' }}</a-button></div>
  </a-drawer>
</template>

<style scoped>
.pipeline-intro { line-height: 1.8; color: var(--muted); margin-block: 8px 20px; }.preparation-notice { margin-block: 16px; }.pipeline-source { display: grid; gap: 14px; margin-block: 24px; padding-bottom: 24px; border-bottom: 1px solid var(--border); }.pipeline-source h3 { font-size: 16px; margin: 0; }
label { display: grid; gap: 8px; font-size: 13px; }input:not([type=file]), textarea, select { box-sizing: border-box; width: 100%; padding: 9px 12px; background: var(--surface); color: var(--text); border: 1px solid var(--border-strong); border-radius: 6px; font: inherit; }textarea { resize: vertical; line-height: 1.7; }input:disabled, textarea:disabled, select:disabled { background: var(--surface-muted); }.pipeline-file { padding: 24px; border: 1px dashed var(--border-strong); border-radius: 8px; background: var(--surface-subtle); }
.pipeline-options { margin-block: 24px; }.pipeline-options summary { cursor: pointer; font-weight: 600; padding-bottom: 18px; }.pipeline-wizard-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 12px; margin-block: 20px; }.preview-issues { color: var(--danger); font-size: 13px; padding-left: 18px; }
</style>
