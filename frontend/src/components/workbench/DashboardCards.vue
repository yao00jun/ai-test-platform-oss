<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { IconEdit, IconRefresh, IconRobot } from '@arco-design/web-vue/es/icon'
import { allAssets, assetApi } from '../../api/assets'
import { evalopsApi, type EvalOps } from '../../api/evalops'
import { workbenchApi, type QualityMetrics } from '../../api/workbench'
import type { Asset, ProjectSummary } from '../../api/types'
import { runLabels } from '../../api/runs'
import { cardDefinitions, defaultDashboardCards, parseDashboardCards, type DashboardCard } from '../../core/dashboard-cards'
import { formatTime } from '../../core/format'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import AssetDetailDrawer from '../assets/AssetDetailDrawer.vue'
import AiDrawer from '../ai/AiDrawer.vue'

const props = defineProps<{ projectId: string; summary?: ProjectSummary; revision: number }>()
const emit = defineEmits<{ changed: [projectId: string]; openRun: [id: string] }>()
const layouts = ref<Asset[]>([]), selectedId = ref(''), initialized = ref(false), loading = ref(false), busy = ref(false)
const quality = ref<QualityMetrics>(), evaluation = ref<EvalOps>(), error = ref<unknown>(), qualityError = ref<unknown>(), evalError = ref<unknown>()
const detailVisible = ref(false), aiVisible = ref(false), aiTarget = ref<Asset>()
const focusedId = ref('')
const detail = ref<InstanceType<typeof AssetDetailDrawer>>()
const scope = new RequestScope()
const selected = computed(() => layouts.value.find(layout => layout.id === selectedId.value))
const rendered = computed(() => {
  try { return { cards: selected.value ? parseDashboardCards(selected.value.data.cards) : defaultDashboardCards(), error: '' } }
  catch (failure) { return { cards: [], error: failure instanceof Error ? failure.message : String(failure) } }
})
const visibleCards = computed(() => rendered.value.cards.filter(card => card.visible))
const selectionKey = () => `ai-test-platform:dashboard:${props.projectId}`
function choose(id: string) { selectedId.value = id; try { localStorage.setItem(selectionKey(), id) } catch { /* Layouts remain usable without browser persistence. */ } }
async function load() {
  const project = props.projectId, token = scope.begin(project)
  loading.value = true; error.value = undefined
  const to = new Date(), from = new Date(to.getTime() - 86_400_000)
  const results = await Promise.allSettled([allAssets(project, 'DASHBOARD', token.signal), workbenchApi.quality(project, token.signal), evalopsApi.summary(project, { from: from.toISOString(), to: to.toISOString() }, token.signal)])
  if (!scope.isCurrent(token)) return
  const [layoutResult, qualityResult, evalResult] = results
  if (layoutResult.status === 'fulfilled') {
    layouts.value = layoutResult.value
    if (!initialized.value) {
      let saved: string | null = null
      try { saved = localStorage.getItem(selectionKey()) } catch { /* Optional local selection. */ }
      selectedId.value = saved === '' || layouts.value.some(layout => layout.id === saved) ? saved! : layouts.value[0]?.id ?? ''
      initialized.value = true
    } else if (selectedId.value && !layouts.value.some(layout => layout.id === selectedId.value)) choose('')
  } else error.value = layoutResult.reason
  quality.value = qualityResult.status === 'fulfilled' ? qualityResult.value : undefined
  qualityError.value = qualityResult.status === 'rejected' ? qualityResult.reason : undefined
  evaluation.value = evalResult.status === 'fulfilled' ? evalResult.value : undefined
  evalError.value = evalResult.status === 'rejected' ? evalResult.reason : undefined
  loading.value = false
}
watch(() => props.projectId, () => {
  scope.invalidate(); initialized.value = false; layouts.value = []; selectedId.value = ''; quality.value = undefined; evaluation.value = undefined
  detailVisible.value = false; focusedId.value = ''; aiVisible.value = false; aiTarget.value = undefined; busy.value = false
  if (props.projectId) void load()
}, { immediate: true })
watch(() => props.revision, () => { if (props.projectId) void load() })
onUnmounted(() => scope.invalidate())

function changed(asset: Asset, updateDetail = true) {
  if (asset.projectId !== props.projectId) return
  const index = layouts.value.findIndex(layout => layout.id === asset.id)
  if (index >= 0) layouts.value[index] = asset
  if (aiTarget.value?.id === asset.id) aiTarget.value = asset
  if (updateDetail) detail.value?.applyAsset(asset)
  emit('changed', props.projectId)
}
function removed(id: string) { layouts.value = layouts.value.filter(layout => layout.id !== id); if (selectedId.value === id) choose(''); emit('changed', props.projectId) }
function openDetail(asset: Asset) { if (asset.projectId === props.projectId) { focusedId.value = asset.id; detailVisible.value = true } }
function refine(asset = selected.value) { if (asset) { aiTarget.value = asset; aiVisible.value = true } }
async function saveDefault() {
  if (busy.value) return
  const project = props.projectId
  busy.value = true; error.value = undefined
  try {
    const asset = await assetApi.create(project, { type: 'DASHBOARD', name: '项目质量看板', data: { cards: defaultDashboardCards(), notes: '' } })
    if (project !== props.projectId) return
    layouts.value.push(asset); initialized.value = true; choose(asset.id); openDetail(asset); emit('changed', project)
  } catch (failure) { if (project === props.projectId) error.value = failure }
  finally { if (project === props.projectId) busy.value = false }
}
const number = (value: number | null | undefined, empty = '暂无样本') => value == null ? empty : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value)
function label(card: DashboardCard, field: string) { return cardDefinitions[card.type].fields.find(value => value.key === field)?.label ?? field }
function metricValue(card: DashboardCard, field: string): string {
  if (card.type === 'metric') return props.summary ? number(card.metric === 'openBugs' ? props.summary.openBugCount : props.summary.counts.FUNCTIONAL_CASE ?? 0, '—') : '—'
  if (card.type === 'assets') return props.summary ? number(props.summary.counts[field] ?? 0) : '—'
  if (card.type === 'quality') {
    const metrics = quality.value
    if (!metrics) return '—'
    if (field === 'p95Ms') return number(metrics.duration.p95Ms)
    if (field === 'pendingCount') return number(['QUEUED', 'RUNNING', 'MANUAL_PENDING'].reduce((sum, status) => sum + (metrics.itemStatuses[status] ?? 0), 0))
    if (field === 'passRatePercent') return metrics.passRatePercent === null ? '暂无样本' : `${number(metrics.passRatePercent)}%`
    return number(metrics[field as 'runCount' | 'itemCount' | 'failureCount'])
  }
  const metrics = evaluation.value
  if (!metrics) return '—'
  if (field === 'invocations') return number(metrics.usage.invocations)
  if (field === 'totalTokens') return number(metrics.usage.totalTokens, '未报告')
  if (field === 'confirmedRegressionBugs') return number(metrics.rca.confirmedRegressionBugs)
  if (field === 'estimatedCost') return metrics.usage.costByCurrency.length ? metrics.usage.costByCurrency.map(cost => `${cost.currency} ${Number(cost.estimatedCost).toFixed(6)}`).join(' · ') : '未计价'
  const rate = field === 'validRatePercent' ? metrics.generation.validRatePercent : field === 'passRatePercent' ? metrics.execution.passRatePercent : metrics.rca.correctRatePercent
  return rate === null ? '暂无样本' : `${number(rate)}%`
}
function bugValue(bug: Asset, field: string) { return field === 'name' ? bug.name : field === 'updatedAt' ? formatTime(bug.updatedAt) : String(bug.data[field] ?? '—') }
function runValue(run: Record<string, unknown>, field: string) {
  if (field === 'createdAt') return formatTime(String(run.createdAt ?? ''))
  if (field === 'status') return runLabels[String(run.status)] ?? String(run.status)
  return String(run.name ?? run.id)
}
</script>

<template>
  <section role="region" aria-label="项目看板" class="dashboard-configured">
    <div class="board-toolbar"><div><h2>项目看板</h2><p class="small muted">{{ selected ? `${selected.name} · v${selected.version}` : '默认概览' }} · 统计来自当前项目</p></div><div class="board-actions"><label class="layout-select">显示布局<select :value="selectedId" aria-label="显示布局" @change="choose(($event.target as HTMLSelectElement).value)"><option value="">默认概览</option><option v-for="layout in layouts" :key="layout.id" :value="layout.id">{{ layout.name }}</option></select></label><a-button :loading="loading" aria-label="刷新看板" @click="load"><IconRefresh /></a-button><template v-if="selected"><a-button @click="openDetail(selected)"><template #icon><IconEdit /></template>编辑当前布局</a-button><a-button @click="refine()"><template #icon><IconRobot /></template>AI 调优整个布局</a-button></template><a-button v-else :loading="busy" @click="saveDefault">保存为我的布局</a-button></div></div>
    <ErrorNotice :error="error" retry @retry="load" />
    <a-alert v-if="rendered.error" type="error" role="alert">{{ rendered.error }} 请编辑当前布局修正配置。</a-alert>
    <p v-if="selected?.data.notes" class="board-notes small">{{ selected.data.notes }}</p>
    <div class="configured-cards">
      <article v-for="card in visibleCards" :key="card.id" :data-card-id="card.id" class="configured-card surface" :class="`card-${card.size}`" :aria-label="card.title">
        <div class="card-heading"><h3>{{ card.title }}</h3><span class="small muted">{{ cardDefinitions[card.type].label }}</span></div>
        <template v-if="card.type === 'bugs' || card.type === 'runs'">
          <div class="card-table-scroll"><table><thead><tr><th v-for="field in card.fields" :key="field">{{ label(card, field) }}</th></tr></thead><tbody v-if="card.type === 'bugs'"><tr v-for="bug in summary?.recentBugs.slice(0, card.limit) ?? []" :key="bug.id"><td v-for="field in card.fields" :key="field"><a-button v-if="field === 'name'" type="text" :aria-label="`查看缺陷 ${bug.name}`" @click="openDetail(bug)">{{ bug.name }}</a-button><span v-else>{{ bugValue(bug, field) }}</span></td></tr><tr v-if="!summary?.recentBugs.length"><td :colspan="card.fields.length" class="muted">{{ summary ? '暂无缺陷记录' : '等待项目概览' }}</td></tr></tbody><tbody v-else><tr v-for="run in summary?.recentRuns.slice(0, card.limit) ?? []" :key="String(run.id)"><td v-for="field in card.fields" :key="field"><a-button v-if="field === 'name'" type="text" :aria-label="`查看运行 ${run.name ?? run.id}`" @click="emit('openRun', String(run.id))">{{ runValue(run, field) }}</a-button><span v-else>{{ runValue(run, field) }}</span></td></tr><tr v-if="!summary?.recentRuns.length"><td :colspan="card.fields.length" class="muted">{{ summary ? '暂无运行记录' : '等待项目概览' }}</td></tr></tbody></table></div>
          <p class="card-footnote small muted">{{ card.type === 'bugs' ? '按更新时间显示最近缺陷' : '按创建时间显示最近运行' }} · 最多 {{ card.limit }} 条</p>
        </template>
        <template v-else>
          <ErrorNotice v-if="card.type === 'quality'" :error="qualityError" />
          <ErrorNotice v-if="card.type === 'evalops'" :error="evalError" />
          <dl class="card-metrics"><div v-for="field in card.fields" :key="field"><dt>{{ card.type === 'metric' ? (card.metric === 'openBugs' ? '未解决缺陷' : '功能用例') : label(card, field) }}</dt><dd :aria-label="card.type === 'metric' ? `${card.title}数值` : label(card, field)">{{ metricValue(card, field) }}</dd></div></dl>
          <p v-if="card.type === 'quality' && quality" class="card-footnote small muted">最近 24 小时 · {{ quality.itemCount }} 个运行项；通过率包含全部运行项，待人工确认项计入分母。</p>
          <p v-if="card.type === 'evalops' && evaluation" class="card-footnote small muted">最近 24 小时 · {{ evaluation.generation.finished }} 项生成、{{ evaluation.execution.executed }} 项执行、{{ evaluation.rca.evaluatedVersions }} 份人工评价。{{ evaluation.usage.usageMissing }} 次调用未报告用量；金额仅为已配置价格的估算。</p>
        </template>
      </article>
    </div>
    <p v-if="!visibleCards.length && !rendered.error" class="board-empty muted">此布局没有可见卡片，可在编辑器中添加或显示卡片。</p>
    <AssetDetailDrawer ref="detail" v-model:visible="detailVisible" :project-id="projectId" :asset-id="focusedId" @changed="changed($event, false)" @removed="removed" @refine="refine" />
    <AiDrawer v-model:visible="aiVisible" :target="aiTarget" @applied="changed" />
  </section>
</template>

<style scoped>
.dashboard-configured { margin-bottom: 26px; min-width: 0; }.board-toolbar { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 18px; }.board-toolbar h2 { font-size: 17px; margin: 0 0 6px; }.board-toolbar p { margin: 0; }.board-actions { display: flex; gap: 8px; align-items: end; flex-wrap: wrap; }.layout-select { display: grid; gap: 5px; color: var(--muted); font-size: 11px; }.layout-select select { padding: 7px 9px; max-width: 230px; border: 1px solid var(--border); border-radius: 6px; color: var(--text); background: var(--color-bg-2, white); }.configured-cards { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 16px; }.configured-card { padding: 20px; min-width: 0; overflow: hidden; }.card-small { grid-column: span 2; }.card-wide { grid-column: span 3; }.card-full { grid-column: span 6; }.card-heading { display: flex; gap: 10px; justify-content: space-between; align-items: baseline; margin-bottom: 18px; }.card-heading h3 { font-size: 15px; margin: 0; overflow-wrap: anywhere; }.card-heading > span { flex-shrink: 0; }.card-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(115px, 1fr)); gap: 18px; margin: 0; }.card-metrics dt { color: var(--muted); font-size: 12px; margin-bottom: 8px; }.card-metrics dd { margin: 0; font-size: 24px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }.card-footnote { margin: 18px 0 0; line-height: 1.7; }.card-table-scroll { width: 100%; overflow: auto; }.card-table-scroll table { border-collapse: collapse; width: 100%; text-align: left; font-size: 12px; }.card-table-scroll th { color: var(--muted); font-weight: 500; }.card-table-scroll td, .card-table-scroll th { padding: 10px 8px; border-bottom: 1px solid var(--border); overflow-wrap: anywhere; max-width: 250px; }.card-table-scroll :deep(.arco-btn) { padding: 0; white-space: normal; height: auto; text-align: left; }.board-notes { margin: 0 0 14px; white-space: pre-wrap; overflow-wrap: anywhere; }.board-empty { padding: 22px; background: var(--color-fill-1); border-radius: 8px; }
@media (max-width: 1050px) { .board-toolbar { align-items: start; flex-direction: column; }.card-small { grid-column: span 3; } }
@media (max-width: 700px) { .card-small, .card-wide, .card-full { grid-column: span 6; }.configured-card { padding: 16px; }.card-heading { flex-wrap: wrap; }.board-actions { width: 100%; }.layout-select { flex: 1; }.layout-select select { width: 100%; max-width: none; } }
</style>
