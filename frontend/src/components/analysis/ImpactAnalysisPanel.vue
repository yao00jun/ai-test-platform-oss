<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { analysisApi, type SourceAnalysis } from '../../api/analysis'
import { impactApi, type ImpactCandidate, type SourceImpact } from '../../api/source-impact'
import { assetApi } from '../../api/assets'
import { ApiError, isRecord } from '../../api/client'
import type { Asset } from '../../api/types'
import { completeImpactSubmission, completeRegressionSubmission, newImpactDraft, readImpactDraft, writeImpactDraft } from '../../core/source-impact-state'
import { RequestScope } from '../../core/request-scope'
import { formatTime } from '../../core/format'
import { terminalJobStates, useJobMonitor } from '../../composables/useJobMonitor'
import ErrorNotice from '../common/ErrorNotice.vue'
import AssetDetailDrawer from '../assets/AssetDetailDrawer.vue'
import AiDrawer from '../ai/AiDrawer.vue'

const props = defineProps<{ projectId: string; source: SourceAnalysis }>()
const project = props.projectId, sourceId = props.source.id
const state = ref(readImpactDraft(project, sourceId) ?? newImpactDraft(project, sourceId))
const history = ref<SourceImpact[]>([]), total = ref(0), page = ref(1), report = ref<SourceImpact>()
const baselines = ref<SourceAnalysis[]>([]), sourceTotal = ref(0), sourceOffset = ref(0), environments = ref<Asset[]>([])
const busy = ref(false), loading = ref(false), error = ref<unknown>(), readError = ref<unknown>(), storageWarning = ref(false)
const baselineLoading = ref(false)
const detailVisible = ref(false), aiVisible = ref(false), aiTarget = ref<Asset>()
const detail = ref<InstanceType<typeof AssetDetailDrawer>>(), showGraph = ref(false)
const scope = new RequestScope(), reads = new RequestScope(), lists = new RequestScope(), token = scope.begin(`${project}:${sourceId}`)
const { job, error: jobError, active, start, stop, cancel } = useJobMonitor()
const data = computed(() => report.value?.result ?? {})
const ready = computed(() => report.value?.status === 'READY')
const locked = computed(() => busy.value || !!state.value.analysisRequest || !!state.value.planRequest)
const hasGitBaseline = computed(() => !!record(record(props.source.result?.origins).BACKEND).baselineRevision)
const candidates = computed(() => objects(data.value.candidates) as unknown as ImpactCandidate[])
const endpointCount = computed(() => new Set(objects(data.value.affectedEndpoints).map(endpoint => `${endpoint.method}:${endpoint.path}`)).size)
const statusLabels: Record<string, string> = { READY: '影响分析已完成', QUEUED: '影响分析排队中', RUNNING: '正在分析变更', FAILED: '影响分析失败', CANCELLED: '影响分析已取消', INTERRUPTED: '影响分析已中断' }
const status = computed(() => statusLabels[report.value?.status ?? ''])
const selectableBaselines = computed(() => baselines.value.filter(item => item.id !== sourceId && ['READY', 'PARTIAL'].includes(item.status)))
function record(value: unknown): Record<string, unknown> { return isRecord(value) ? value : {} }
function objects(value: unknown): Record<string, unknown>[] { return Array.isArray(value) ? value.filter(isRecord) : [] }
watch(state, () => { storageWarning.value = !writeImpactDraft(state.value) }, { deep: true, flush: 'sync' })
watch(() => job.value?.status, value => { if (value && terminalJobStates.has(value)) void refresh() })
async function loadBaselines() {
  if (baselineLoading.value) return
  const offset = sourceOffset.value; baselineLoading.value = true
  try {
    const result = await analysisApi.list(project, offset, token.signal)
    if (!scope.isCurrent(token)) return
    baselines.value = [...new Map([...baselines.value, ...result.items].map(item => [item.id, item])).values()]
    sourceOffset.value = offset + result.items.length; sourceTotal.value = result.total
  } catch (failure) { if (scope.isCurrent(token)) readError.value = failure }
  finally { if (scope.isCurrent(token)) baselineLoading.value = false }
}
async function loadEnvironments() {
  try {
    const entries: Asset[] = []; let total = 1
    while (entries.length < total) {
      const result = await assetApi.list(project, { type: 'ENVIRONMENT', offset: entries.length, limit: 100 }, token.signal)
      entries.push(...result.items); total = result.total; if (!result.items.length) break
    }
    if (scope.isCurrent(token)) environments.value = entries
  } catch (failure) { if (scope.isCurrent(token)) readError.value = failure }
}
async function list() {
  const read = lists.begin(token.key); loading.value = true
  try {
    const result = await impactApi.list(project, sourceId, (page.value - 1) * 25, read.signal)
    if (!scope.isCurrent(token) || !lists.isCurrent(read)) return
    history.value = result.items; total.value = result.total
    if (!state.value.selectedImpactId && !state.value.analysisRequest && result.items[0]) await select(result.items[0].id)
  } catch (failure) { if (scope.isCurrent(token) && lists.isCurrent(read)) readError.value = failure }
  finally { if (scope.isCurrent(token) && lists.isCurrent(read)) loading.value = false }
}
async function select(id: string) {
  const read = reads.begin(id)
  if (state.value.selectedImpactId !== id) { state.value.selectedIds = []; state.value.planId = ''; report.value = undefined }
  state.value.selectedImpactId = id
  try {
    const result = await impactApi.get(project, id, read.signal)
    if (!scope.isCurrent(token) || !reads.isCurrent(read)) return
    report.value = result
    if (['READY', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(result.status)) stop()
    else if (job.value?.id !== result.jobId) await start(project, result.jobId)
  } catch (failure) { if (scope.isCurrent(token) && reads.isCurrent(read)) readError.value = failure }
}
async function refresh() {
  readError.value = undefined
  await Promise.allSettled([list(), state.value.selectedImpactId ? select(state.value.selectedImpactId) : Promise.resolve()])
}
async function reloadContext() { await Promise.allSettled([refresh(), loadBaselines(), loadEnvironments()]) }
async function analyze() {
  if (busy.value || state.value.planRequest) return
  if (!state.value.analysisRequest) state.value.analysisRequest = { baselineSnapshotId: state.value.baselineId, idempotencyKey: crypto.randomUUID() }
  const input = { ...state.value.analysisRequest }; busy.value = true; error.value = undefined
  try {
    const result = await impactApi.submit(project, sourceId, input)
    const recovered = completeImpactSubmission(project, sourceId, input.idempotencyKey, result)
    if (!scope.isCurrent(token)) return
    if (recovered) state.value = recovered
    report.value = undefined; page.value = 1; await select(result.impactId); void list()
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && [400, 403, 404, 422].includes(failure.status)) state.value.analysisRequest = undefined
  } finally { if (scope.isCurrent(token)) busy.value = false }
}
async function createPlan() {
  if (busy.value || (!state.value.planRequest && (!ready.value || !state.value.selectedIds.length))) return
  if (!state.value.planRequest) state.value.planRequest = { impactId: state.value.selectedImpactId, body: { assetIds: [...state.value.selectedIds], name: state.value.planName, environmentId: state.value.environmentId, idempotencyKey: crypto.randomUUID() } }
  const pending = { impactId: state.value.planRequest.impactId, body: { ...state.value.planRequest.body, assetIds: [...state.value.planRequest.body.assetIds] } }; busy.value = true; error.value = undefined
  try {
    const result = await impactApi.plan(project, pending.impactId, pending.body)
    const recovered = completeRegressionSubmission(project, sourceId, pending.body.idempotencyKey, result)
    if (scope.isCurrent(token) && recovered) state.value = recovered
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && ([400, 403, 404, 422].includes(failure.status) || failure.code === 'IMPACT_CANDIDATE_CHANGED')) state.value.planRequest = undefined
  } finally { if (scope.isCurrent(token)) busy.value = false }
}
function refine(asset: Asset) { aiTarget.value = asset; aiVisible.value = true }
function applied(asset: Asset) { detail.value?.applyAsset(asset); if (aiTarget.value?.id === asset.id) aiTarget.value = asset }
void Promise.allSettled([loadBaselines(), loadEnvironments(), refresh()])
onUnmounted(() => { scope.invalidate(); reads.invalidate(); lists.invalidate(); stop() })
</script>

<template>
  <section class="impact-panel" role="region" aria-label="源码变更与定向回归">
    <header class="impact-heading"><div><h3>源码变更与定向回归</h3><p class="small muted">按固定源码的实际改动定位方法，再沿调用关系查找候选测试。</p></div><a-button size="small" :loading="loading" @click="reloadContext">刷新影响报告</a-button></header>
    <div class="impact-form">
      <label>对比基线快照<select v-model="state.baselineId" aria-label="对比基线快照" :disabled="locked || active"><option value="">{{ hasGitBaseline ? '使用此快照已采集的 Git 基线' : '请选择旧源码快照' }}</option><option v-if="state.baselineId && !selectableBaselines.some(item => item.id === state.baselineId)" :value="state.baselineId">已选基线 {{ state.baselineId.slice(0, 8) }}</option><option v-for="item in selectableBaselines" :key="item.id" :value="item.id">{{ formatTime(item.createdAt) }} · {{ item.id.slice(0, 8) }} · {{ item.fileCount }} 文件</option></select></label>
      <a-button type="primary" :loading="busy && !!state.analysisRequest" :disabled="!!state.planRequest || active || (!state.analysisRequest && !state.baselineId && !hasGitBaseline)" @click="analyze">{{ state.analysisRequest ? '恢复影响分析提交' : '分析变更影响' }}</a-button>
      <a-button v-if="sourceOffset < sourceTotal" size="small" @click="loadBaselines">加载更多基线</a-button><a-button v-if="active" size="small" status="warning" @click="cancel">取消影响分析</a-button>
    </div>
    <p v-if="state.analysisRequest || state.planRequest" class="small">提交结果待确认。恢复会使用原来的输入和选择，不会重复创建记录。</p>
    <p v-if="storageWarning" role="alert" class="small">浏览器无法保存恢复信息，请保留当前页面直到提交确认。</p>
    <ErrorNotice :error="error" /><ErrorNotice :error="readError" retry @retry="reloadContext" /><ErrorNotice :error="jobError" />
    <div v-if="history.length" class="impact-history"><label>影响分析历史<select :value="state.selectedImpactId" aria-label="影响分析历史" :disabled="locked" @change="select(($event.target as HTMLSelectElement).value)"><option v-for="item in history" :key="item.id" :value="item.id">{{ formatTime(item.createdAt) }} · {{ item.id.slice(0, 8) }} · {{ item.status }}</option></select></label><a-pagination v-if="total > 25" v-model:current="page" simple :total="total" :page-size="25" :disabled="locked" @change="list" /></div>
    <template v-if="report">
      <p><strong data-testid="impact-status">{{ status }}</strong><span v-if="active" class="small muted"> · {{ job?.progress }}% · {{ job?.message }}</span></p>
      <p v-if="report.error" role="alert" class="small">{{ report.error }}</p>
      <template v-if="ready">
        <div class="impact-facts"><span>方法变更 <strong>{{ objects(data.changedMethods).length }}</strong></span><span>影响接口 <strong>{{ endpointCount }}</strong></span><span>候选测试 <strong>{{ candidates.length }}</strong></span></div>
        <div class="impact-grid">
          <article data-testid="changed-methods"><h4>精确方法变更</h4><p v-for="(method, index) in objects(data.changedMethods)" :key="index"><strong>{{ method.action }} · {{ method.signature }}</strong><small>{{ method.sourcePath }}:{{ method.startLine }}–{{ method.endLine }}</small></p><p v-if="!objects(data.changedMethods).length" class="small muted">没有方法行范围变更。文件或类声明的变化可在完整报告中查看。</p></article>
          <article><h4>影响接口与表</h4><p v-for="(endpoint, index) in objects(data.affectedEndpoints)" :key="`endpoint-${index}`"><strong>{{ endpoint.method }} {{ endpoint.path }}</strong><small>{{ endpoint.sourceVersion }} · {{ endpoint.reason }}</small><small>{{ Array.isArray(endpoint.callPath) ? endpoint.callPath.join(' → ') : '' }}</small></p><p v-for="(table, index) in objects(data.affectedTables)" :key="`table-${index}`"><strong>{{ table.table }}</strong><small>{{ table.sourcePath }}:{{ table.startLine }} · {{ table.basis }}</small></p></article>
        </div>
        <div v-if="objects(data.diagnostics).length || objects(data.sourceDiagnostics).length || objects(data.baselineDiagnostics).length || objects(data.unmappedEndpoints).length" class="impact-diagnostics"><p v-for="(item, index) in [...objects(data.diagnostics), ...objects(data.sourceDiagnostics), ...objects(data.baselineDiagnostics)]" :key="index"><strong>{{ item.code }}</strong> · {{ item.message }}</p><p v-for="(item, index) in objects(data.unmappedEndpoints)" :key="`unmapped-${index}`">尚未匹配测试：{{ item.method }} {{ item.path }}</p></div>
        <p class="small muted">静态分析不能确定反射、运行时注入与所有动态调用。请结合解析诊断和业务风险审阅回归范围。</p>
        <div class="impact-candidates"><h4>选择回归测试 · 已选 {{ state.selectedIds.length }}</h4><label v-for="candidate in candidates" :key="candidate.assetId"><input v-model="state.selectedIds" type="checkbox" :value="candidate.assetId" :aria-label="`选择回归测试 ${candidate.name}`" :disabled="locked"><span><strong>{{ candidate.name }}</strong><small>{{ candidate.assetType }} · v{{ candidate.version }}</small><small v-for="reason in candidate.reasons" :key="reason">{{ reason }}</small></span></label><p v-if="!candidates.length" class="small muted">没有已映射的候选测试。可先维护相关接口用例，再重新分析资产映射。</p></div>
        <div class="impact-plan-form"><label>回归计划名称<input v-model="state.planName" aria-label="回归计划名称" maxlength="200" :disabled="locked" placeholder="留空使用默认名称"></label><label>回归执行环境<select v-model="state.environmentId" aria-label="回归执行环境" :disabled="locked"><option value="">继承所选计划项环境，或运行时指定</option><option v-for="environment in environments" :key="environment.id" :value="environment.id">{{ environment.name }}</option></select></label></div>
      </template>
    </template>
    <div v-if="ready || state.planRequest" class="impact-actions"><a-button type="primary" :loading="busy && !!state.planRequest" :disabled="busy || !!state.analysisRequest || (!state.planRequest && !state.selectedIds.length)" @click="createPlan">{{ state.planRequest ? '恢复回归计划提交' : '创建选定回归计划' }}</a-button><template v-if="state.planId"><span role="status" class="small">回归计划已创建</span><a-button @click="detailVisible = true">打开回归计划</a-button><RouterLink to="/plans">前往测试计划执行</RouterLink></template></div>
    <details v-if="ready" class="impact-json" @toggle="showGraph = ($event.target as HTMLDetailsElement).open"><summary>完整差异、调用图与未解析调用</summary><pre v-if="showGraph">{{ JSON.stringify(data, null, 2) }}</pre></details>
    <AssetDetailDrawer v-if="state.planId" ref="detail" v-model:visible="detailVisible" :project-id="project" :asset-id="state.planId" @refine="refine" @changed="applied" />
    <AiDrawer v-model:visible="aiVisible" :target="aiTarget" @applied="applied" />
  </section>
</template>

<style scoped>
.impact-panel { border-top: 1px solid var(--border); padding-top: 22px; margin-top: 24px; min-width: 0; overflow-wrap: anywhere; }.impact-heading { display: flex; justify-content: space-between; align-items: start; gap: 12px; flex-wrap: wrap; }h3 { margin: 0; font-size: 15px; }h4 { font-size: 13px; margin: 0 0 12px; }.impact-form, .impact-history, .impact-actions { display: flex; align-items: end; gap: 10px; flex-wrap: wrap; margin: 15px 0; }.impact-form label, .impact-history label { flex: 1; min-width: 200px; }.impact-panel label { display: grid; gap: 7px; font-size: 12px; min-width: 0; }.impact-panel select, .impact-panel input:not([type=checkbox]) { font: inherit; color: var(--text); width: 100%; box-sizing: border-box; border: 1px solid var(--border); border-radius: 6px; padding: 9px; background: var(--surface); min-width: 0; }.impact-facts { display: flex; gap: 20px; flex-wrap: wrap; background: var(--info-soft); border-radius: 7px; padding: 14px; font-size: 12px; }.impact-facts strong { margin-left: 7px; color: var(--primary); }.impact-grid, .impact-plan-form { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; margin-top: 16px; }.impact-grid article { border: 1px solid var(--border); border-radius: 7px; padding: 14px; min-width: 0; max-height: 350px; overflow: auto; font-size: 12px; }.impact-panel small { display: block; color: var(--muted); font-size: 11px; margin-top: 5px; line-height: 1.6; }.impact-diagnostics { background: var(--warning-soft); border-left: 3px solid var(--warning-border); padding: 6px 12px; margin-top: 12px; font-size: 12px; }.impact-candidates { margin-top: 20px; }.impact-candidates label { display: flex; align-items: start; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 6px; margin: 7px 0; }.impact-candidates input { accent-color: var(--primary); margin-top: 2px; flex: none; }.impact-candidates span { min-width: 0; }.impact-json { margin-top: 18px; font-size: 12px; }.impact-json summary { cursor: pointer; }.impact-json pre { white-space: pre; overflow: auto; background: var(--surface-code); padding: 12px; max-height: 420px; }.impact-actions { align-items: center; }.impact-actions a { font-size: 12px; }@media (max-width: 760px) { .impact-grid, .impact-plan-form { grid-template-columns: minmax(0,1fr); }.impact-form label, .impact-history label { flex-basis: 100%; } }
</style>
