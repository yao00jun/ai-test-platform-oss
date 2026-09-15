<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { evalopsApi, type EvalOps, type MetricsWindow, type ModelInvocation } from '../../api/evalops'
import { RequestScope } from '../../core/request-scope'
import { formatTime, jobLabels } from '../../core/format'
import { useWorkspaceStore } from '../../stores/workspace'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ projectId: string }>()
const workspace = useWorkspaceStore()
const metrics = ref<EvalOps>(), error = ref<unknown>(), loading = ref(false), range = ref('all')
const details = ref(false), calls = ref<ModelInvocation[]>([]), total = ref(0), page = ref(1), selectedModel = ref('')
const callError = ref<unknown>(), callLoading = ref(false)
const scope = new RequestScope(), callScope = new RequestScope()
const activeWindow = computed<MetricsWindow>(() => ({ from: metrics.value?.from, to: metrics.value?.to }))
const modelKey = (model: { modelName: string; modelVersion: string }) => JSON.stringify([model.modelName, model.modelVersion])
const selected = computed(() => metrics.value?.byModel.find(model => modelKey(model) === selectedModel.value))
const modelOptions = computed(() => [{ value: '', label: '全部模型版本' }, ...(metrics.value?.byModel ?? []).map(model => ({ value: modelKey(model), label: `${model.modelName} · 配置 ${model.modelVersion}` }))])
const percent = (value: number | null) => value === null ? '暂无样本' : `${value}%`
const tokens = (value: number | null) => value === null ? '未报告' : value.toLocaleString('zh-CN')
const amount = (value: number | string) => Number(value).toLocaleString('en-US', { maximumFractionDigits: 12, useGrouping: false })

async function load() {
  if (!props.projectId) return
  const token = scope.begin(props.projectId); loading.value = true; error.value = undefined
  const now = new Date(), days = Number(range.value)
  const window: MetricsWindow = { to: now.toISOString(), ...(Number.isFinite(days) ? { from: new Date(now.getTime() - days * 86400000).toISOString() } : {}) }
  try {
    const value = await evalopsApi.summary(token.key, window, token.signal)
    if (!scope.isCurrent(token)) return
    metrics.value = value
    if (!value.byModel.some(model => modelKey(model) === selectedModel.value)) selectedModel.value = ''
    if (details.value) void loadCalls()
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
async function loadCalls() {
  if (!details.value || !metrics.value) return
  const token = callScope.begin(props.projectId); callLoading.value = true; callError.value = undefined
  try {
    const value = await evalopsApi.invocations(token.key, activeWindow.value, (page.value - 1) * 10, selected.value?.modelName, selected.value?.modelVersion, token.signal)
    if (callScope.isCurrent(token)) { calls.value = value.items; total.value = value.total }
  } catch (failure) { if (callScope.isCurrent(token)) callError.value = failure }
  finally { if (callScope.isCurrent(token)) callLoading.value = false }
}
function toggleDetails() { details.value = !details.value; if (details.value) void loadCalls(); else { callScope.invalidate(); callLoading.value = false } }
watch(() => props.projectId, () => {
  scope.invalidate(); callScope.invalidate(); metrics.value = undefined; calls.value = []; total.value = 0; page.value = 1; details.value = false; selectedModel.value = ''; callError.value = undefined; callLoading.value = false; void load()
}, { immediate: true })
watch(range, () => { page.value = 1; void load() })
watch(selectedModel, () => { page.value = 1; void loadCalls() })
watch(page, () => { void loadCalls() })
watch(() => workspace.activityRevision, () => { void load() })
onUnmounted(() => { scope.invalidate(); callScope.invalidate() })
</script>

<template>
  <section aria-label="AI 效能评估" :data-project-id="projectId" class="surface evalops-panel">
    <div class="surface-heading"><div><h2>AI 效能评估</h2><p class="small muted">实际调用、生成验证、运行快照与人工评价。</p></div><div class="inline-actions"><a-select v-model="range" aria-label="效能统计时间范围" :options="[{ value: 'all', label: '全部记录' }, { value: '1', label: '最近 24 小时' }, { value: '7', label: '最近 7 天' }, { value: '30', label: '最近 30 天' }]" style="width: 150px" /><a-button :loading="loading" @click="load">刷新效能</a-button></div></div>
    <ErrorNotice :error="error" retry @retry="load" />
    <a-spin v-if="loading && !metrics" tip="载入实测指标…" />
    <div v-if="metrics" class="eval-body">
      <div class="eval-grid">
        <div class="eval-stat"><span>生成有效率</span><strong aria-label="生成有效率">{{ percent(metrics.generation.validRatePercent) }}</strong><p>{{ metrics.generation.finished }} 次生成完成，{{ metrics.generation.valid }} 次有效</p><p>每个任务计一次；格式修复不增加分母。</p></div>
        <div class="eval-stat"><span>AI 资产执行通过率</span><strong aria-label="AI 资产执行通过率">{{ percent(metrics.execution.passRatePercent) }}</strong><p>{{ metrics.execution.passed }} / {{ metrics.execution.executed }} 个实际执行项通过</p><p>依据运行时 AI 来源；DDT 每行计一项。</p></div>
        <div class="eval-stat"><span>人工 RCA 正确率</span><strong aria-label="人工 RCA 正确率">{{ percent(metrics.rca.correctRatePercent) }}</strong><p>{{ metrics.rca.correct }} / {{ metrics.rca.evaluatedVersions }} 个已评价诊断版本正确</p><p>部分正确 {{ metrics.rca.partial }} · 错误 {{ metrics.rca.incorrect }} · 撤回评价 {{ metrics.rca.clearedVersions }}</p></div>
        <div class="eval-stat"><span>已报告 Token</span><strong aria-label="已报告 Token">{{ tokens(metrics.usage.totalTokens) }}</strong><p>{{ metrics.usage.usageReported }} / {{ metrics.usage.invocations }} 次调用报告用量</p><p>输入 {{ tokens(metrics.usage.promptTokens) }} · 输出 {{ tokens(metrics.usage.completionTokens) }}</p></div>
      </div>
      <div class="eval-context">
        <p>{{ metrics.usage.succeeded }} 次请求正常完成 · {{ metrics.usage.failed }} 次失败 · {{ metrics.usage.httpAttempts }} 次 HTTP 尝试。取消 {{ metrics.usage.cancelled }}，中断 {{ metrics.usage.interrupted }}，进行中 {{ metrics.usage.active }}。</p>
        <p>生成阻塞 {{ metrics.generation.blocked }} · 取消 {{ metrics.generation.cancelled }} · 中断 {{ metrics.generation.interrupted }}；这些任务不计入有效率分母。运行阻塞 {{ metrics.execution.blocked }} · 待执行 {{ metrics.execution.pending }} · 跳过 {{ metrics.execution.skipped }} · 取消或中断 {{ metrics.execution.interrupted }}。</p>
        <p>人工确认退化缺陷 {{ metrics.rca.confirmedRegressionBugs }} 个。尚无全部退化缺陷基数，拦截率待评估；模型自报置信度不参与准确率。</p>
        <p v-if="metrics.usage.costByCurrency.length">已计价调用估算：<span v-for="cost in metrics.usage.costByCurrency" :key="cost.currency" class="cost">{{ amount(cost.estimatedCost) }} {{ cost.currency }}（{{ cost.invocations }} 次）</span>。其余 {{ metrics.usage.invocations - metrics.usage.pricedInvocations }} 次无可计算金额。</p>
        <p v-else>费用未知：尚无同时具备已启用价目和输入、输出用量的调用。</p>
        <p>统计截至 {{ formatTime(metrics.to) }}。缺失用量保持未知；金额按调用时价目估算，最终以供应商账单为准。</p>
      </div>
      <a-button :aria-expanded="details" @click="toggleDetails">{{ details ? '收起调用记录' : '查看调用记录' }}</a-button>
      <div v-if="details" class="eval-details">
        <a-select v-model="selectedModel" aria-label="调用模型版本" :options="modelOptions" class="model-filter" />
        <p v-if="selected" class="small muted">此版本 {{ selected.generation.finished }} 次生成完成，{{ selected.generation.valid }} 次有效；有效率 {{ percent(selected.generation.validRatePercent) }}。同一任务跨模型调用时可计入多个版本组。</p>
        <ErrorNotice :error="callError" retry @retry="loadCalls" />
        <div v-if="callLoading" class="small muted" role="status">载入调用记录…</div>
        <div class="invocation-scroll"><table aria-label="模型调用记录" class="invocation-table"><thead><tr><th>时间 / 模型</th><th>任务 / 状态</th><th>用量 / 耗时</th><th>历史价目 / 估算</th></tr></thead><tbody><tr v-for="call in calls" :key="call.id"><td>{{ formatTime(call.startedAt) }}<br><strong>{{ call.modelName }}</strong><br>配置 {{ call.modelVersion }}<br>响应模型 {{ call.responseModel || '未报告' }}</td><td>{{ call.templateName }}<br>{{ call.purpose === 'FORMAT_REPAIR' ? '格式修复' : '初次调用' }} · {{ jobLabels[call.status] || call.status }}<br>{{ call.httpAttempts }} 次 HTTP 尝试<span v-if="call.errorCode"><br>{{ call.errorCode }}</span></td><td>{{ tokens(call.totalTokens) }}<br>输入 {{ tokens(call.promptTokens) }} / 输出 {{ tokens(call.completionTokens) }}<br>{{ call.durationMs === null ? '耗时未知' : `${call.durationMs} ms` }}</td><td>{{ call.pricingVersion ? `价目版本 ${call.pricingVersion}` : '未启用价目' }}<br>{{ call.estimatedCost === null ? '金额未知' : `${amount(call.estimatedCost)} ${call.currency}` }}</td></tr><tr v-if="!calls.length && !callLoading"><td colspan="4">此范围暂无调用记录。</td></tr></tbody></table></div>
        <a-pagination v-model:current="page" :total="total" :page-size="10" simple show-total />
      </div>
    </div>
  </section>
</template>

<style scoped>
.evalops-panel { margin: 24px 0; min-width: 0; }
.eval-body { padding: 20px 24px 24px; min-width: 0; }
.eval-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.eval-stat { min-width: 0; border: 1px solid var(--color-border-2); border-radius: 10px; padding: 18px; }
.eval-stat > span { color: var(--color-text-2); font-size: 13px; }
.eval-stat strong { display: block; font-size: 27px; margin: 10px 0; }
.eval-stat p { font-size: 12px; line-height: 1.7; color: var(--color-text-3); margin: 5px 0 0; }
.eval-context { margin: 20px 0; font-size: 12px; color: var(--color-text-3); line-height: 1.8; }
.cost { display: inline-block; color: var(--color-text-1); margin-right: 10px; }
.eval-details { margin-top: 18px; min-width: 0; }
.model-filter { max-width: 100%; width: 420px; }
.invocation-scroll { overflow-x: auto; margin: 16px 0; max-width: 100%; }
.invocation-table { width: 100%; min-width: 670px; border-collapse: collapse; font-size: 12px; text-align: left; line-height: 1.8; }
.invocation-table th, .invocation-table td { border-bottom: 1px solid var(--color-border-2); padding: 12px; vertical-align: top; overflow-wrap: anywhere; max-width: 270px; }
.invocation-table th { background: var(--color-fill-1); }
@media (max-width: 1100px) { .eval-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .eval-grid { grid-template-columns: 1fr; } .evalops-panel .surface-heading { flex-wrap: wrap; gap: 12px; } .eval-stat { padding: 14px; } .eval-body { padding: 16px; } }
</style>
