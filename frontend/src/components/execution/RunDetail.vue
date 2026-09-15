<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { runApi, runLabels, type DiagnosisOccurrence, type RunDetail, type RunItem } from '../../api/runs'
import type { Asset, GenerationScope } from '../../api/types'
import { ApiError, saveDownload } from '../../api/client'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { formatTime, jobLabels } from '../../core/format'
import { useJobMonitor, jobFailure, terminalJobStates } from '../../composables/useJobMonitor'
import { useWorkspaceStore } from '../../stores/workspace'
import ErrorNotice from '../common/ErrorNotice.vue'
import ManualResultForm from './ManualResultForm.vue'
import ManualInstructions from './ManualInstructions.vue'
import StepEvidence from './StepEvidence.vue'
import DiagnosisOccurrenceCard from './DiagnosisOccurrenceCard.vue'
import AiDrawer from '../ai/AiDrawer.vue'

const AssetDetailDrawer = defineAsyncComponent(() => import('../assets/AssetDetailDrawer.vue'))

const props = defineProps<{ projectId: string; runId: string }>()
const emit = defineEmits<{ back: []; updated: [] }>()
const workspace = useWorkspaceStore()
const run = ref<RunDetail>(), occurrences = ref<DiagnosisOccurrence[]>([])
const loading = ref(false), error = ref<unknown>(), diagnosisError = ref<unknown>()
const downloadBusy = ref(false), diagnosing = ref(false), format = ref('html')
const reportAiVisible = ref(false), reportVisible = ref(false), reportId = ref(''), reportTarget = ref<Asset>()
const reportDetail = ref<{ applyAsset: (asset: Asset) => void }>()
const reportGeneration = computed<GenerationScope>(() => ({ projectId: props.projectId, type: 'QUALITY_BRIEF', runId: props.runId, label: `${run.value?.name ?? '当前运行'} · 质量报告` }))
const scope = new RequestScope(), reads = new RequestScope(), monitor = useJobMonitor(), diagnosisMonitor = useJobMonitor()
let current: ScopeToken | undefined, monitoredJob = '', diagnosisKey: string | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let retryDelay = 2000
const hasFailures = computed(() => ['FAILED', 'ERROR', 'BLOCKED', 'INTERRUPTED'].some(status => (run.value?.summary.counts[status] ?? 0) > 0))
const diagnosisFailure = computed(() => diagnosisMonitor.job.value && ['FAILED', 'INTERRUPTED'].includes(diagnosisMonitor.job.value.status) ? jobFailure(diagnosisMonitor.job.value) : undefined)
const functionalSteps = computed(() => {
  const groups = new Map<string, Asset[]>()
  for (const asset of Object.values(run.value?.snapshot.assets ?? {})) {
    if (asset.type !== 'FUNCTIONAL_STEP' || !asset.parentId) continue
    const steps = groups.get(asset.parentId) ?? []
    steps.push(asset); groups.set(asset.parentId, steps)
  }
  for (const steps of groups.values()) steps.sort((left, right) => left.position - right.position || left.id.localeCompare(right.id))
  return groups
})
function manual(item: RunItem) { return item.assetType === 'FUNCTIONAL_CASE' || item.status === 'MANUAL_PENDING' || Number(item.manualVersion) > 1 }
function changed() { workspace.recordActivity(props.projectId); emit('updated') }
function runState(value?: RunDetail) {
  if (!value) return undefined
  const { summary } = value
  return JSON.stringify([value.status, summary.total, summary.caseCount, summary.dataRows, Object.entries(summary.counts).sort(([left], [right]) => left.localeCompare(right))])
}
function scheduleRefresh(token: ScopeToken, delay: number) {
  timer = setTimeout(() => { if (scope.isCurrent(token)) void refresh() }, delay)
}

watch(() => [props.projectId, props.runId], () => {
  scope.invalidate(); reads.invalidate(); monitor.stop(); diagnosisMonitor.stop()
  clearTimeout(timer)
  run.value = undefined; occurrences.value = []; error.value = undefined; diagnosisError.value = undefined
  reportAiVisible.value = false; reportVisible.value = false; reportId.value = ''; reportTarget.value = undefined
  loading.value = false; downloadBusy.value = false; diagnosing.value = false; monitoredJob = ''; diagnosisKey = undefined; retryDelay = 2000
  current = scope.begin(`${props.projectId}:${props.runId}`)
  void refresh()
}, { immediate: true })
async function refresh() {
  const token = current
  if (!token || !scope.isCurrent(token) || !props.runId) return
  const query = reads.begin(`${props.projectId}:${props.runId}`)
  clearTimeout(timer)
  loading.value = true
  const results = await Promise.allSettled([runApi.get(props.projectId, props.runId, query.signal), runApi.diagnoses(props.projectId, props.runId, query.signal)])
  if (!scope.isCurrent(token) || !reads.isCurrent(query)) return
  loading.value = false
  if (results[0].status === 'fulfilled') {
    const value = results[0].value
    const stateChanged = runState(run.value) !== runState(value)
    run.value = value; error.value = undefined; retryDelay = 2000
    if (monitoredJob !== value.jobId) { monitoredJob = value.jobId; void monitor.start(props.projectId, value.jobId) }
    if (stateChanged) changed()
    if (['QUEUED', 'RUNNING'].includes(value.status)) scheduleRefresh(token, 2000)
  } else {
    const failure = results[0].reason
    error.value = failure
    if (run.value && ['QUEUED', 'RUNNING'].includes(run.value.status) && failure instanceof ApiError && (failure.status === 0 || failure.status === 408 || failure.status === 429 || failure.status >= 500)) {
      // Keep the last persisted run visible while a transient read retries.
      // The capped delay and view token bound polling to this unfinished run.
      scheduleRefresh(token, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10000)
    }
  }
  if (results[1].status === 'fulfilled') { occurrences.value = results[1].value.items; diagnosisError.value = undefined }
  else diagnosisError.value = results[1].reason
}
watch(() => [monitor.job.value?.id, monitor.job.value?.status, monitor.job.value?.progress], () => {
  const job = monitor.job.value
  if (!job || job.id !== monitoredJob) return
  void refresh()
})
watch(() => diagnosisMonitor.job.value?.status, status => { if (status && terminalJobStates.has(status)) { void refresh(); changed() } })
function saved(value: RunDetail) {
  if (value.id !== props.runId || value.projectId !== props.projectId) return
  reads.invalidate(); loading.value = false; run.value = value; error.value = undefined; changed()
}
async function download() {
  const token = current
  if (!token || downloadBusy.value) return
  downloadBusy.value = true; error.value = undefined
  try { const file = await runApi.report(props.projectId, props.runId, format.value, token.signal); if (scope.isCurrent(token)) saveDownload(file) }
  catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) downloadBusy.value = false }
}
async function diagnose() {
  const token = current
  if (!token || diagnosing.value || diagnosisMonitor.active.value) return
  diagnosisKey ??= crypto.randomUUID(); diagnosing.value = true; diagnosisError.value = undefined
  try {
    const accepted = await runApi.diagnose(props.projectId, props.runId, diagnosisKey)
    if (!scope.isCurrent(token)) return
    diagnosisKey = undefined; await diagnosisMonitor.start(props.projectId, accepted.jobId)
  } catch (failure) { if (scope.isCurrent(token)) diagnosisError.value = failure }
  finally { if (scope.isCurrent(token)) diagnosing.value = false }
}
function generateReport() { reportTarget.value = undefined; reportAiVisible.value = true }
function reportGenerated(assets: Asset[], restored: boolean) {
  if (restored) return
  const brief = assets.find(asset => asset.projectId === props.projectId && asset.type === 'QUALITY_BRIEF' && asset.data.runId === props.runId)
  if (!brief) return
  reportId.value = brief.id; reportAiVisible.value = false; reportVisible.value = true; changed()
}
function refineReport(asset: Asset) { if (asset.projectId === props.projectId) { reportTarget.value = asset; reportAiVisible.value = true } }
function reportChanged(asset: Asset) {
  if (asset.projectId !== props.projectId) return
  if (reportTarget.value?.id === asset.id) reportTarget.value = asset
  changed()
}
function reportApplied(asset: Asset) { reportDetail.value?.applyAsset(asset); reportChanged(asset) }
onUnmounted(() => { scope.invalidate(); reads.invalidate(); clearTimeout(timer) })
</script>

<template>
  <div class="run-detail">
    <div class="run-detail-toolbar"><a-button type="text" @click="emit('back')">返回执行列表</a-button><a-button :loading="loading" @click="refresh">刷新运行</a-button></div>
    <ErrorNotice :error="error ?? monitor.error.value" retry @retry="refresh" />
    <a-spin v-if="loading && !run" tip="读取运行快照与结果…" />
    <template v-if="run">
      <div class="run-heading"><div><h2>{{ run.name }}</h2><p class="small muted">{{ formatTime(run.createdAt) }} · <code>{{ run.id }}</code></p></div><a-tag data-testid="run-status" :color="run.status === 'PASSED' ? 'green' : ['FAILED', 'ERROR'].includes(run.status) ? 'red' : 'arcoblue'">{{ runLabels[run.status] ?? run.status }}</a-tag></div>
      <div class="run-counts"><div><span>运行项</span><strong aria-label="运行项总数">{{ run.summary.total }}</strong></div><div><span>去重用例</span><strong aria-label="去重用例数">{{ run.summary.caseCount }}</strong></div><div><span>数据行</span><strong aria-label="数据行数">{{ run.summary.dataRows }}</strong></div><div v-for="(count, state) in run.summary.counts" :key="state"><span>{{ runLabels[state] ?? state }}</span><strong>{{ count }}</strong></div></div>
      <div v-if="monitor.job.value" class="run-job"><span class="small muted">调度任务：{{ jobLabels[monitor.job.value.status] }} {{ monitor.job.value.message }}</span><a-progress v-if="monitor.active.value" :percent="monitor.job.value.progress / 100" :show-text="false" /><a-button v-if="monitor.active.value" status="danger" size="small" @click="monitor.cancel">取消此次运行</a-button><p v-if="monitor.reconnecting.value" class="small muted">连接正在恢复，使用持久化任务状态继续追踪。</p></div>
      <div class="run-download"><label>报告格式<select v-model="format" aria-label="报告格式" :disabled="downloadBusy"><option v-for="value in ['html', 'pdf', 'json', 'zip', 'csv', 'xlsx']" :key="value" :value="value">{{ value.toUpperCase() }}</option></select></label><a-button :loading="downloadBusy" @click="download">下载运行报告</a-button><a-button @click="generateReport">AI 质量报告</a-button><a-button v-if="hasFailures" :loading="diagnosing" :disabled="diagnosisMonitor.active.value" @click="diagnose">AI 诊断失败并记录缺陷</a-button></div>
      <ErrorNotice :error="diagnosisError ?? diagnosisMonitor.error.value ?? diagnosisFailure" />
      <div v-if="diagnosisMonitor.job.value" class="inline-actions small"><span>失败诊断：{{ jobLabels[diagnosisMonitor.job.value.status] }}</span><a-button v-if="diagnosisMonitor.active.value" size="small" @click="diagnosisMonitor.cancel">取消诊断</a-button></div>
      <details v-if="occurrences.length" class="run-diagnoses">
        <summary>诊断与缺陷记录 · {{ occurrences.length }}</summary>
        <DiagnosisOccurrenceCard v-for="occurrence in occurrences" :key="occurrence.id" :occurrence="occurrence" />
      </details>
      <article v-for="item in run.items" :key="item.id" class="run-item"><header><div><h3>{{ item.name }}</h3><p class="small muted">{{ workspace.label(item.assetType) }}{{ item.rowIndex === null ? '' : ` · 数据行 ${item.rowIndex + 1}` }}{{ item.durationMs === null ? '' : ` · ${item.durationMs} ms` }}</p></div><a-tag :color="item.status === 'PASSED' ? 'green' : ['FAILED', 'ERROR'].includes(item.status) ? 'red' : 'gray'">{{ runLabels[item.status] ?? item.status }}</a-tag></header>
        <p v-if="item.error" class="run-item-error">{{ item.error }}</p>
        <details v-if="item.rowIndex !== null"><summary class="small muted">本行变量</summary><pre class="json-view">{{ JSON.stringify(item.variables, null, 2) }}</pre></details>
        <template v-if="manual(item)"><ManualInstructions :name="item.name" :asset="run.snapshot.assets[item.assetId]" :steps="functionalSteps.get(item.assetId) ?? []" /><ManualResultForm :project-id="projectId" :run-id="runId" :item="item" @saved="saved" /></template>
        <StepEvidence v-for="step in item.steps" :key="step.id" :project-id="projectId" :step="step" />
      </article>
      <details class="run-snapshot"><summary>运行时资产快照</summary><p class="small muted">执行当时的名称、版本、步骤与配置；后续资产编辑不会修改这份记录。</p><pre data-testid="run-snapshot" class="json-view">{{ JSON.stringify(run.snapshot, null, 2) }}</pre></details>
    </template>
    <AssetDetailDrawer v-if="reportId" ref="reportDetail" v-model:visible="reportVisible" :project-id="projectId" :asset-id="reportId" @changed="reportChanged" @refine="refineReport" />
    <AiDrawer v-model:visible="reportAiVisible" :target="reportTarget" :generation="reportTarget ? undefined : reportGeneration" @generated="reportGenerated" @applied="reportApplied" />
  </div>
</template>

<style scoped>
.run-detail-toolbar, .run-heading, .run-item header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }.run-heading { margin-block: 20px; }h2, h3 { margin: 0; }h2 { font-size: 20px; }h3 { font-size: 15px; }
.run-heading code { overflow-wrap: anywhere; }.run-counts { display: flex; flex-wrap: wrap; gap: 12px; margin-block: 20px; }.run-counts > div { flex: 1; min-width: 75px; padding: 14px 18px; background: #f6f7fb; border-radius: 8px; }.run-counts span { display: block; color: var(--muted); font-size: 12px; }.run-counts strong { display: block; font-size: 24px; margin-top: 6px; }
.run-job { display: grid; gap: 10px; margin-bottom: 18px; }.run-download { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; margin-block: 18px; }.run-download label { display: grid; gap: 6px; font-size: 12px; }select { padding: 7px 12px; background: white; border: 1px solid var(--border); border-radius: 6px; color: var(--text); }
.run-item { border-block-start: 1px solid var(--border); padding: 22px 0; }.run-item header { margin-bottom: 16px; }.run-item-error { color: #b22f45; overflow-wrap: anywhere; white-space: pre-wrap; }.run-snapshot { margin: 24px 0; }.run-diagnoses { margin-block: 18px; }.json-view { max-height: 480px; overflow: auto; }summary { cursor: pointer; }
</style>
