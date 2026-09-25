<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { analysisApi, sourceSettingNames, sourceStatusLabels, type SourceAnalysis, type SourceExcerpt, type SourceFile } from '../../api/analysis'
import { assetApi } from '../../api/assets'
import { ApiError, isRecord } from '../../api/client'
import type { Asset } from '../../api/types'
import { completeSourceSubmission, createSourceDraft, readSourceDraft, sourceSettings, writeSourceDraft } from '../../core/source-analysis-state'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { formatTime } from '../../core/format'
import { terminalJobStates, useJobMonitor } from '../../composables/useJobMonitor'
import ErrorNotice from '../common/ErrorNotice.vue'
import ImpactAnalysisPanel from './ImpactAnalysisPanel.vue'

const props = defineProps<{ project: Asset }>()
const emit = defineEmits<{ changed: [] }>()
const state = ref(readSourceDraft(props.project.id) ?? createSourceDraft(props.project))
const history = ref<SourceAnalysis[]>([]), total = ref(0), page = ref(1), selected = ref<SourceAnalysis>()
const files = ref<SourceFile[]>([]), fileCount = ref(0), filePage = ref(1), excerpt = ref<SourceExcerpt>()
const loading = ref(false), saving = ref(false), submitting = ref(false), readingFile = ref(false), latest = ref<Asset>()
const error = ref<unknown>(), readError = ref<unknown>(), success = ref(''), recoveryWarning = ref(false)
const scope = new RequestScope(), listReads = new RequestScope(), detailReads = new RequestScope(), fileReads = new RequestScope()
let current: ScopeToken | undefined
const { job, error: jobError, active: jobActive, start: monitorStart, stop: monitorStop, cancel: cancelJob } = useJobMonitor()
const patch = computed(() => Object.fromEntries(sourceSettingNames.filter(key => state.value.draft[key] !== state.value.original[key]).map(key => [key, state.value.draft[key]])))
const dirty = computed(() => Object.keys(patch.value).length > 0)
const inputLocked = computed(() => submitting.value || !!state.value.request)
const canAnalyze = computed(() => [state.value.draft.backendRepoPath, state.value.draft.frontendRepoPath, state.value.draft.sqlScriptPath, state.value.draft.ddlText].some(value => !!value.trim()))
const ready = computed(() => selected.value && ['READY', 'PARTIAL'].includes(selected.value.status))
const status = computed(() => selected.value && (job.value?.id === selected.value.jobId && !ready.value ? job.value.status : selected.value.status))
const backend = computed(() => record(selected.value?.result?.backend)), frontend = computed(() => record(selected.value?.result?.frontend))
const tables = computed(() => objects(record(selected.value?.result?.database).tables))
const origins = computed(() => Object.entries(record(selected.value?.result?.origins)).filter(([, value]) => !!record(value).location))
// Errors and warnings are real gaps (they make a snapshot "partial"); INFO items only explain normal code, e.g. MyBatis <if>.
const gaps = computed(() => (selected.value?.diagnostics ?? []).filter(item => item.severity !== 'INFO'))
const notes = computed(() => (selected.value?.diagnostics ?? []).filter(item => item.severity === 'INFO'))
const severityLabels: Record<string, string> = { ERROR: '错误', WARNING: '缺口' }
function record(value: unknown): Record<string, unknown> { return isRecord(value) ? value : {} }
function objects(value: unknown): Record<string, unknown>[] { return Array.isArray(value) ? value.filter(isRecord) : [] }
function persist() { recoveryWarning.value = !writeSourceDraft(state.value) }

watch(state, persist, { deep: true })
watch(() => props.project.id, () => {
  scope.invalidate(); listReads.invalidate(); detailReads.invalidate(); fileReads.invalidate(); monitorStop()
  state.value = readSourceDraft(props.project.id) ?? createSourceDraft(props.project)
  current = scope.begin(props.project.id)
  history.value = []; selected.value = undefined; files.value = []; excerpt.value = undefined
  error.value = undefined; readError.value = undefined; latest.value = undefined; success.value = ''; page.value = 1
  loading.value = false; saving.value = false; submitting.value = false; readingFile.value = false
  void refresh()
}, { immediate: true })
watch(() => props.project.version, () => {
  if (!dirty.value && props.project.id === state.value.projectId) {
    state.value.baseVersion = props.project.version; state.value.original = sourceSettings(props.project)
    state.value.draft = { ...state.value.draft, ...state.value.original }
  }
})
watch(() => job.value?.status, value => {
  if (value && terminalJobStates.has(value) && selected.value?.jobId === job.value?.id) void refresh()
})
async function loadHistory() {
  const token = current
  if (!token) return
  const query = listReads.begin(token.key); loading.value = true
  try {
    const result = await analysisApi.list(token.key, (page.value - 1) * 25, query.signal)
    if (!scope.isCurrent(token) || !listReads.isCurrent(query)) return
    history.value = result.items; total.value = result.total; readError.value = undefined
    if (!state.value.selectedId && !state.value.request && result.items[0]) void select(result.items[0].id)
  } catch (failure) { if (scope.isCurrent(token) && listReads.isCurrent(query)) readError.value = failure }
  finally { if (scope.isCurrent(token) && listReads.isCurrent(query)) loading.value = false }
}
async function refresh() {
  await Promise.allSettled([loadHistory(), state.value.selectedId ? select(state.value.selectedId) : Promise.resolve()])
}
async function select(id: string) {
  const token = current
  if (!token) return
  const query = detailReads.begin(`${token.key}:${id}`)
  if (selected.value?.id !== id) { fileReads.invalidate(); files.value = []; excerpt.value = undefined; filePage.value = 1; selected.value = undefined }
  state.value.selectedId = id; persist()
  try {
    const result = await analysisApi.get(token.key, id, query.signal)
    if (!scope.isCurrent(token) || !detailReads.isCurrent(query)) return
    selected.value = result; readError.value = undefined
    if (['READY', 'PARTIAL'].includes(result.status)) { monitorStop(); await loadFiles() }
    else if (job.value?.id !== result.jobId && !['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(result.status)) await monitorStart(token.key, result.jobId)
  } catch (failure) { if (scope.isCurrent(token) && detailReads.isCurrent(query)) readError.value = failure }
}
async function loadFiles() {
  const token = current, snapshot = selected.value
  if (!token || !snapshot || !ready.value) return
  const query = fileReads.begin(`${token.key}:${snapshot.id}:list`)
  try {
    const result = await analysisApi.files(token.key, snapshot.id, (filePage.value - 1) * 100, query.signal)
    if (scope.isCurrent(token) && fileReads.isCurrent(query)) { files.value = result.items; fileCount.value = result.total; readError.value = undefined }
  } catch (failure) { if (scope.isCurrent(token) && fileReads.isCurrent(query)) readError.value = failure }
}
async function openFile(file: Pick<SourceFile, 'kind' | 'path'>, from = 1) {
  const token = current, snapshot = selected.value
  if (!token || !snapshot) return
  const query = fileReads.begin(`${token.key}:${snapshot.id}:${file.kind}:${file.path}`); readingFile.value = true
  try {
    const result = await analysisApi.excerpt(token.key, snapshot.id, file, from, query.signal)
    if (scope.isCurrent(token) && fileReads.isCurrent(query)) { excerpt.value = result; readError.value = undefined }
  } catch (failure) { if (scope.isCurrent(token) && fileReads.isCurrent(query)) readError.value = failure }
  finally { if (scope.isCurrent(token) && fileReads.isCurrent(query)) readingFile.value = false }
}
async function submit() {
  const token = current
  if (!token || submitting.value) return
  if (!state.value.request) state.value.request = { ...state.value.draft, idempotencyKey: crypto.randomUUID() }
  const request = { ...state.value.request }; persist(); submitting.value = true; error.value = undefined; success.value = ''
  try {
    const result = await analysisApi.submit(token.key, request)
    const saved = completeSourceSubmission(token.key, request.idempotencyKey, result)
    if (!scope.isCurrent(token)) return
    if (saved) state.value = saved
    page.value = 1; await select(result.analysisId); void loadHistory()
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && [400, 403, 404, 422].includes(failure.status)) { state.value.request = undefined; persist() }
  } finally { if (scope.isCurrent(token)) submitting.value = false }
}
async function save() {
  const token = current, change = { ...patch.value }, baseVersion = state.value.baseVersion
  if (!token || saving.value || !dirty.value) return
  saving.value = true; error.value = undefined; latest.value = undefined; success.value = ''
  try {
    const updated = await assetApi.patch(token.key, token.key, { baseVersion, data: change })
    if (!scope.isCurrent(token)) return
    state.value.original = sourceSettings(updated); state.value.baseVersion = updated.version
    state.value.draft = { ...state.value.draft, ...state.value.original }; persist(); success.value = '源码设置已保存'; emit('changed')
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && failure.status === 409) {
      try { const result = await assetApi.get(token.key, token.key, token.signal); if (scope.isCurrent(token)) latest.value = result }
      catch (problem) { if (scope.isCurrent(token)) readError.value = problem }
    }
  } finally { if (scope.isCurrent(token)) saving.value = false }
}
function rebase() {
  if (!latest.value) return
  const change = { ...patch.value }, original = sourceSettings(latest.value)
  state.value.baseVersion = latest.value.version; state.value.original = original
  state.value.draft = { ...state.value.draft, ...original, ...change }; latest.value = undefined; error.value = undefined; persist(); emit('changed')
}
onUnmounted(() => { persist(); scope.invalidate(); listReads.invalidate(); detailReads.invalidate(); fileReads.invalidate() })
</script>

<template>
  <section class="surface source-panel" role="region" aria-label="五类输入与源码证据">
    <header class="source-heading"><div><h2>五类输入与源码证据</h2><p class="small muted">为测试资产补充代码分支、接口映射、页面属性与数据库结构。</p></div><a-button size="small" :loading="loading" @click="refresh">刷新源码历史</a-button></header>
    <div class="input-dimensions"><span>① 需求文档 · 项目资源</span><span>② 接口契约 · 接口测试</span><span>③ 后端源码</span><span>④ 前端源码</span><span>⑤ 数据库 DDL</span></div>
    <form class="source-form" @submit.prevent="submit">
      <label>后端源码路径<input v-model="state.draft.backendRepoPath" aria-label="后端源码路径" :disabled="inputLocked || saving" placeholder="本地绝对目录或 HTTP(S) Git 地址"></label>
      <label>前端源码路径<input v-model="state.draft.frontendRepoPath" aria-label="前端源码路径" :disabled="inputLocked || saving" placeholder="Vue / React 源码目录或 Git 地址"></label>
      <label class="source-wide">SQL 脚本路径<input v-model="state.draft.sqlScriptPath" aria-label="SQL 脚本路径" :disabled="inputLocked || saving" placeholder="本地 .sql 文件绝对路径，可与下方 DDL 一起分析"></label>
      <div class="source-wide inline-actions"><a-button :loading="saving" :disabled="!dirty || inputLocked" @click="save">保存项目源码设置</a-button><span v-if="success" role="status" class="source-success small">{{ success }}</span><span v-if="dirty" class="small muted">路径有未保存修改 · 当前基于 v{{ state.baseVersion }}</span></div>
      <label>后端 Git 版本<input v-model="state.draft.backendRef" aria-label="后端 Git 版本" :disabled="inputLocked" placeholder="本地留空读取工作区；Git 地址默认 HEAD"></label>
      <label>对比基线版本<input v-model="state.draft.baselineRef" aria-label="对比基线版本" :disabled="inputLocked" placeholder="提交、分支或标签；填写后后端默认读取 HEAD"></label>
      <label>前端 Git 版本<input v-model="state.draft.frontendRef" aria-label="前端 Git 版本" :disabled="inputLocked" placeholder="可选提交、分支或标签"></label>
      <label class="source-wide">补充 DDL<textarea v-model="state.draft.ddlText" aria-label="补充 DDL" rows="4" :disabled="inputLocked" placeholder="粘贴 CREATE TABLE 定义，与已有文件共同保存为不可变快照"></textarea></label>
      <p class="small muted source-wide">本地路径由后端服务读取。每次分析保留文件哈希与版本，之后修改源文件不会改变历史证据。静态页面属性需要在实际页面验证。</p>
      <div class="source-wide inline-actions"><a-button type="primary" html-type="submit" :loading="submitting" :disabled="!canAnalyze || saving">{{ state.request ? '恢复源码分析提交' : '分析源码与 DDL' }}</a-button><a-button v-if="jobActive" status="warning" @click="cancelJob">取消当前分析</a-button><span v-if="jobActive" class="small muted">{{ job?.message }} · {{ job?.progress }}%</span></div>
      <p v-if="state.request && !submitting" class="small source-wide">提交结果尚未确认。恢复会使用原输入找回同一次分析。</p>
      <p v-if="recoveryWarning" role="alert" class="small source-wide">浏览器暂时无法保存恢复信息，请保留当前页面直到提交结果确认。</p>
    </form>
    <ErrorNotice :error="error" /><ErrorNotice :error="readError" retry @retry="refresh" /><ErrorNotice :error="jobError" />
    <div v-if="latest" class="source-conflict"><p>项目已更新为 v{{ latest.version }}。你的路径修改已保留。</p><a-button size="small" @click="rebase">保留我的路径修改并使用最新版本</a-button></div>
    <div class="source-layout">
      <aside class="source-history"><h3>分析历史 · {{ total }}</h3><p v-if="!history.length" class="small muted">还没有源码分析记录。</p><button v-for="item in history" :key="item.id" type="button" :class="{ selected: selected?.id === item.id }" :aria-label="`查看源码快照 ${item.id.slice(0, 8)}`" @click="select(item.id)"><strong>{{ formatTime(item.createdAt) }}</strong><span>{{ sourceStatusLabels[item.status] ?? item.status }}</span><small>{{ item.fileCount }} 个文件 · {{ item.id.slice(0, 8) }}</small></button><a-pagination v-if="total > 25" v-model:current="page" simple :total="total" :page-size="25" @change="loadHistory" /></aside>
      <div v-if="selected" class="source-detail">
        <div class="source-heading"><h3>源码快照 {{ selected.id.slice(0, 8) }}</h3><a-tag :color="selected.status === 'READY' ? 'green' : selected.status === 'PARTIAL' ? 'orange' : 'gray'" data-testid="source-analysis-status">{{ sourceStatusLabels[status ?? ''] ?? status }}</a-tag></div>
        <p class="small muted">项目 v{{ selected.projectVersion }} · {{ selected.fileCount }} 个文件 · {{ (selected.totalBytes / 1024).toFixed(1) }} KB</p>
        <p v-if="selected.error" role="alert" class="source-failure">{{ selected.error }}</p>
        <p v-for="[kind, origin] in origins" :key="kind" class="small source-origin"><strong>{{ kind }}</strong> {{ record(origin).location }}<br>版本：{{ record(origin).revision }}<template v-if="record(origin).baselineRevision"> · 基线：{{ record(origin).baselineRevision }}</template></p>
        <ul v-if="gaps.length" class="source-diagnostics" aria-label="解析诊断"><li v-for="(item, index) in gaps" :key="index"><a-tag size="small" :color="item.severity === 'ERROR' ? 'red' : 'orange'">{{ severityLabels[item.severity] ?? item.severity }}</a-tag> <strong>{{ item.code }}</strong> · {{ item.path }}{{ item.line ? `:${item.line}` : '' }}<br>{{ item.message }}</li></ul>
        <details v-if="notes.length" class="source-notes"><summary>{{ notes.length }} 条分析提示（正常写法的说明，不影响分析完整度）</summary><ul class="source-diagnostics" aria-label="分析提示"><li v-for="(item, index) in notes" :key="index"><strong>{{ item.code }}</strong> · {{ item.path }}{{ item.line ? `:${item.line}` : '' }}<br>{{ item.message }}</li></ul></details>
        <template v-if="ready">
          <ImpactAnalysisPanel :key="`${project.id}:${selected.id}`" :project-id="project.id" :source="selected" />
          <div class="source-evidence-grid">
            <article><h4>后端接口 · {{ objects(backend.endpoints).length }}</h4><p v-for="(item, index) in objects(backend.endpoints).slice(0, 80)" :key="index"><strong>{{ item.method }} {{ item.path }}</strong><small>{{ item.sourcePath }}:{{ item.startLine }}</small></p><p v-if="!objects(backend.endpoints).length" class="muted">未提取到静态接口映射。</p></article>
            <article><h4>业务分支与校验 · {{ objects(backend.branches).length + objects(backend.constraints).length }}</h4><p v-for="(item, index) in objects(backend.branches).slice(0, 40)" :key="`branch-${index}`"><code>{{ item.condition || item.effect }}</code><small>{{ item.sourcePath }}:{{ item.startLine }}</small></p><p v-for="(item, index) in objects(backend.constraints).slice(0, 40)" :key="`constraint-${index}`">{{ item.target }} · @{{ item.annotation }} {{ item.value }}<small>{{ item.sourcePath }}:{{ item.line }}</small></p></article>
            <article><h4>页面定位依据 · {{ objects(frontend.selectors).length }}</h4><p v-for="(item, index) in objects(frontend.selectors).slice(0, 80)" :key="index"><code>{{ item.selector }}</code><small>{{ item.sourcePath }}:{{ item.line }} · {{ item.conditional ? '按条件呈现' : '静态属性' }} · 待页面验证</small></p></article>
            <article><h4>数据库结构 · {{ tables.length }}</h4><p v-for="(table, index) in tables.slice(0, 80)" :key="index"><strong>{{ table.name }}</strong><small>{{ objects(table.columns).map(column => `${column.name} ${column.type}`).join('；') }}</small></p><p v-if="!tables.length" class="muted">没有可用的建表定义。</p></article>
          </div>
          <details class="source-json"><summary>完整解析证据（上方每组最多显示 80 项）</summary><pre>{{ JSON.stringify(selected.result, null, 2) }}</pre></details>
          <h4>固定文件 · {{ fileCount }}</h4><div class="source-files"><button v-for="file in files" :key="`${file.kind}:${file.path}`" type="button" :aria-label="`查看源码 ${file.kind} / ${file.path}`" @click="openFile(file)"><span>{{ file.kind }}</span>{{ file.path }}</button></div><a-pagination v-if="fileCount > 100" v-model:current="filePage" simple :total="fileCount" :page-size="100" @change="loadFiles" />
          <div v-if="excerpt" class="source-code"><div class="source-heading"><strong>{{ excerpt.kind }} / {{ excerpt.path }}</strong><span class="small muted">{{ excerpt.from }}–{{ excerpt.to }} / {{ excerpt.totalLines }} 行</span></div><p class="small muted">SHA-256：{{ excerpt.sha256 }}</p><pre data-testid="source-content">{{ excerpt.content }}</pre><div class="inline-actions"><a-button size="small" :disabled="readingFile || excerpt.from <= 1" @click="openFile(excerpt, Math.max(1, excerpt.from - 100))">上一段源码</a-button><a-button size="small" :disabled="readingFile || excerpt.to >= excerpt.totalLines" @click="openFile(excerpt, excerpt.to + 1)">下一段源码</a-button></div></div>
        </template>
      </div>
      <div v-else class="source-empty"><h3>把测试与实现连接起来</h3><p class="muted">输入代码目录或 DDL 后，可以查看后端隐藏分支、接口映射、前端属性与真实表结构。</p></div>
    </div>
  </section>
</template>

<style scoped>
.source-panel { padding: 22px; margin-block: 24px; min-width: 0; overflow-wrap: anywhere; }.source-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }.source-heading h2 { font-size: 18px; margin: 0; }h3 { margin: 0 0 12px; font-size: 15px; }h4 { margin: 18px 0 12px; font-size: 13px; }.input-dimensions { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0; }.input-dimensions span { font-size: 11px; border: 1px solid var(--border); background: var(--surface-subtle); border-radius: 20px; padding: 6px 10px; }.source-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; margin-block: 20px; }.source-form label { display: grid; gap: 7px; font-size: 12px; }.source-form input, .source-form textarea { box-sizing: border-box; width: 100%; min-width: 0; font: inherit; padding: 10px; color: var(--text); border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }.source-form textarea { resize: vertical; }.source-form :disabled { background: var(--surface-muted); }.source-wide { grid-column: 1 / -1; }.inline-actions { flex-wrap: wrap; }.source-success { color: var(--success); }.source-layout { display: grid; grid-template-columns: 205px minmax(0, 1fr); gap: 22px; border-top: 1px solid var(--border); padding-top: 22px; }.source-history button { display: grid; gap: 7px; text-align: left; width: 100%; font: inherit; font-size: 11px; color: var(--text); background: var(--surface); border: 1px solid var(--border); border-radius: 7px; padding: 12px; margin-bottom: 8px; cursor: pointer; }.source-history button.selected { border-color: var(--primary); background: var(--primary-soft); }.source-history small { color: var(--muted); }.source-detail, .source-history { min-width: 0; }.source-origin { border-left: 2px solid var(--info-border); padding-left: 10px; line-height: 1.6; }.source-diagnostics { padding: 14px 14px 14px 30px; background: var(--warning-soft); border: 1px solid var(--warning-border); border-radius: 6px; font-size: 12px; }.source-diagnostics li + li { margin-top: 10px; }.source-notes { margin-block: 10px; font-size: 12px; }.source-notes summary { cursor: pointer; color: var(--muted); }.source-notes .source-diagnostics { margin-top: 8px; background: var(--surface-soft, transparent); border-color: var(--border); }.source-failure { color: var(--danger); font-size: 12px; }.source-evidence-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.source-evidence-grid article { min-width: 0; border: 1px solid var(--border); padding: 0 14px 8px; border-radius: 7px; font-size: 12px; max-height: 350px; overflow: auto; }.source-evidence-grid small { display: block; color: var(--muted); font-size: 11px; line-height: 1.7; margin-top: 5px; }.source-json { margin-block: 18px; font-size: 12px; }.source-json summary { cursor: pointer; }.source-json pre, .source-code pre { overflow: auto; white-space: pre; font-size: 12px; line-height: 1.7; background: var(--surface-code); border: 1px solid var(--border); border-radius: 6px; padding: 14px; max-height: 420px; }.source-files { display: flex; gap: 8px; flex-wrap: wrap; }.source-files button { min-width: 0; max-width: 100%; font: inherit; font-size: 11px; color: var(--primary); border: 1px solid var(--border); border-radius: 5px; padding: 7px 10px; background: var(--surface); cursor: pointer; overflow-wrap: anywhere; }.source-files span { font-size: 9px; color: var(--muted); margin-right: 7px; }.source-code { min-width: 0; margin-top: 20px; }.source-conflict { border-left: 3px solid var(--warning-border); padding: 12px; margin-bottom: 18px; font-size: 12px; }.source-empty { padding: 35px 12px; line-height: 1.7; }.source-empty p { max-width: 470px; font-size: 13px; }@media (max-width: 850px) { .source-layout { grid-template-columns: minmax(0, 1fr); }.source-history { max-height: 260px; overflow: auto; } }@media (max-width: 560px) { .source-panel { padding: 16px; }.source-form, .source-evidence-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
