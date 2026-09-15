<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { scheduleApi, occurrenceLabels, type ScheduleState } from '../../api/schedules'
import { assetApi } from '../../api/assets'
import { ApiError } from '../../api/client'
import type { Asset } from '../../api/types'
import { runLabels } from '../../api/runs'
import { buildAssetPatch } from '../../core/asset-patch'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const RunHistoryDrawer = defineAsyncComponent(() => import('../execution/RunHistoryDrawer.vue'))
const props = defineProps<{ asset: Asset }>()
const emit = defineEmits<{ changed: [asset: Asset] }>()
const keys = ['cronExpression', 'timezone', 'scheduleEnabled', 'overlapPolicy', 'misfirePolicy']
function fields(asset: Asset) { return { cronExpression: String(asset.data.cronExpression ?? ''), timezone: String(asset.data.timezone ?? ''), scheduleEnabled: asset.data.scheduleEnabled === true, overlapPolicy: String(asset.data.overlapPolicy ?? 'SKIP'), misfirePolicy: String(asset.data.misfirePolicy ?? 'SKIP') } }
const baseline = ref(props.asset), draft = ref(fields(props.asset)), latest = ref<Asset>()
const state = ref<ScheduleState>(), page = ref(1), loading = ref(false), saving = ref(false), previewing = ref(false)
const readError = ref<unknown>(), error = ref<unknown>(), success = ref(''), preview = ref<{ timezone: string; nextFireTimes: string[] }>()
const runVisible = ref(false), runId = ref('')
const scope = new RequestScope(), reads = new RequestScope(), previews = new RequestScope()
let current: ScopeToken | undefined, timer: ReturnType<typeof setTimeout> | undefined
const patch = computed(() => buildAssetPatch(baseline.value, { data: draft.value }, keys))
watch([() => props.asset.projectId, () => props.asset.id], () => {
  scope.invalidate(); reads.invalidate(); previews.invalidate(); clearTimeout(timer)
  baseline.value = props.asset; draft.value = fields(props.asset); latest.value = undefined; state.value = undefined
  page.value = 1; error.value = undefined; readError.value = undefined; success.value = ''; preview.value = undefined
  loading.value = false; saving.value = false; previewing.value = false; runVisible.value = false
  current = scope.begin(`${props.asset.projectId}:${props.asset.id}`); void load()
}, { immediate: true })
watch(() => props.asset, asset => { if (asset.id === baseline.value.id && !patch.value) { baseline.value = asset; draft.value = fields(asset) } })
watch(() => [draft.value.cronExpression, draft.value.timezone], () => { previews.invalidate(); previewing.value = false; preview.value = undefined })
async function load() {
  const token = current
  if (!token || !scope.isCurrent(token)) return
  clearTimeout(timer); const query = reads.begin(token.key); loading.value = true
  try {
    const result = await scheduleApi.state(props.asset.projectId, props.asset.id, (page.value - 1) * 25, query.signal)
    if (scope.isCurrent(token) && reads.isCurrent(query)) { state.value = result; readError.value = undefined }
  } catch (failure) { if (scope.isCurrent(token) && reads.isCurrent(query)) readError.value = failure }
  finally {
    if (scope.isCurrent(token) && reads.isCurrent(query)) {
      loading.value = false
      if (state.value?.configuration.enabled || state.value?.queuedCount) timer = setTimeout(() => void load(), readError.value ? 5000 : 2000)
    }
  }
}
async function save() {
  const token = current, change = patch.value
  if (!token || saving.value || !change) return
  saving.value = true; error.value = undefined; latest.value = undefined; success.value = ''
  try {
    const updated = await scheduleApi.save(props.asset.projectId, props.asset.id, { baseVersion: change.baseVersion, ...change.data })
    if (!scope.isCurrent(token)) return
    baseline.value = updated; draft.value = fields(updated); emit('changed', updated); success.value = '排期已保存'; void load()
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && failure.status === 409) {
      try { const value = await assetApi.get(props.asset.projectId, props.asset.id, token.signal); if (scope.isCurrent(token)) latest.value = value }
      catch (readFailure) { if (scope.isCurrent(token)) readError.value = readFailure }
    }
  } finally { if (scope.isCurrent(token)) saving.value = false }
}
function rebase() {
  if (!latest.value) return
  const changes = patch.value?.data ?? {}, value = latest.value
  baseline.value = value; draft.value = { ...fields(value), ...changes }; latest.value = undefined; error.value = undefined
  emit('changed', value)
}
async function previewTimes() {
  const token = current
  if (!token || previewing.value) return
  const query = previews.begin(token.key); previewing.value = true; error.value = undefined
  try {
    const result = await scheduleApi.preview(props.asset.projectId, props.asset.id, { cronExpression: draft.value.cronExpression, timezone: draft.value.timezone }, query.signal)
    if (scope.isCurrent(token) && previews.isCurrent(query)) preview.value = result
  } catch (failure) { if (scope.isCurrent(token) && previews.isCurrent(query)) error.value = failure }
  finally { if (scope.isCurrent(token) && previews.isCurrent(query)) previewing.value = false }
}
function showRun(id: string) { runId.value = id; runVisible.value = true }
function at(value: string | null, zone?: string) {
  if (!value) return '—'
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone || state.value?.configuration.timezone || 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) }
  catch { return value }
}
onUnmounted(() => { scope.invalidate(); reads.invalidate(); previews.invalidate(); clearTimeout(timer) })
</script>

<template>
  <section class="schedule-panel" role="region" aria-label="巡检排期">
    <div class="schedule-heading"><div><h3>定时巡检</h3><p class="small muted">前次执行结束后按策略派发。人工确认结果不会阻塞后续巡检。</p></div><a-button size="small" :loading="loading" @click="load">刷新排期记录</a-button></div>
    <ErrorNotice :error="readError" retry @retry="load" />
    <form class="schedule-form" aria-label="巡检设置" @submit.prevent="save">
      <label class="schedule-enabled"><input v-model="draft.scheduleEnabled" type="checkbox" aria-label="启用巡检" :disabled="saving">启用巡检</label>
      <label>Cron 表达式<input v-model="draft.cronExpression" aria-label="巡检 Cron" :disabled="saving" placeholder="0 0 9 * * *"></label>
      <label>时区<input v-model="draft.timezone" aria-label="巡检时区" :disabled="saving" :placeholder="state?.configuration.timezone ?? 'Asia/Shanghai'"></label>
      <label>重叠执行策略<select v-model="draft.overlapPolicy" aria-label="重叠执行策略" :disabled="saving"><option value="SKIP">跳过：前次仍运行时不再派发</option><option value="QUEUE">排队：等待前次结束后派发</option></select></label>
      <label>错过触发策略<select v-model="draft.misfirePolicy" aria-label="错过触发策略" :disabled="saving"><option value="SKIP">跳过错过的触发</option><option value="FIRE_ONCE">将错过的区间合并补跑一次</option></select></label>
      <p class="small muted schedule-help">Cron 为六个字段：秒 分 时 日 月 周。停用或修改排期会取消尚未派发的旧触发，已运行任务继续执行。</p>
      <div class="inline-actions schedule-actions"><a-button :loading="previewing" :disabled="saving || !draft.cronExpression.trim()" @click="previewTimes">预览触发时间</a-button><a-button type="primary" html-type="submit" :loading="saving" :disabled="!patch">保存排期</a-button><span v-if="success" role="status" class="small schedule-success">{{ success }}</span></div>
    </form>
    <ErrorNotice :error="error" />
    <div v-if="latest" class="schedule-conflict"><p>当前已是 v{{ latest.version }}，你的未保存设置仍然保留。</p><a-button size="small" @click="rebase">保留我的修改并使用最新版本</a-button></div>
    <div v-if="preview" class="schedule-preview"><strong>未来 5 次触发 · {{ preview.timezone }}</strong><ol aria-label="未来触发时间"><li v-for="time in preview.nextFireTimes" :key="time">{{ at(time, preview.timezone) }}</li></ol><p v-if="!preview.nextFireTimes.length" class="small muted">此表达式没有未来触发时间。</p></div>
    <template v-if="state">
      <div class="schedule-summary"><span>{{ state.configuration.enabled ? '已启用' : '已停用' }} · {{ state.configuration.timezone }}</span><span>下一次：{{ at(state.nextFireAt) }}</span><span>排队 {{ state.queuedCount }} / {{ state.maxQueued }}</span></div>
      <p v-if="!state.schedulerEnabled" class="small muted">服务器已关闭自动调度，当前不会自动派发。</p>
      <p class="small muted">晚到超过 {{ state.misfireGraceSeconds }} 秒按错过策略处理。派发时保存最新测试资产快照，历史记录保留触发时与派发时的计划版本。</p>
      <h3>触发记录 · {{ state.total }}</h3>
      <p v-if="!state.items.length" class="small muted">还没有触发记录。</p>
      <article v-for="item in state.items" :key="item.id" class="schedule-occurrence" :aria-label="`巡检触发 ${item.id}`"><header><strong>{{ at(item.scheduledFor, item.configuration.timezone) }}</strong><a-tag :color="item.status === 'FAILED' ? 'red' : item.status === 'SUBMITTED' ? 'arcoblue' : 'gray'">{{ occurrenceLabels[item.status] ?? item.status }}</a-tag></header><p>{{ item.reason }}</p><p v-if="item.misfireThrough" class="small muted">合并区间截至 {{ at(item.misfireThrough, item.configuration.timezone) }}</p><div class="schedule-record-footer"><span class="small muted">触发版本 v{{ item.planVersion }}{{ item.dispatchedPlanVersion ? ` · 派发版本 v${item.dispatchedPlanVersion}` : '' }}{{ item.runStatus ? ` · 测试结果：${runLabels[item.runStatus] ?? item.runStatus}` : '' }}</span><a-button v-if="item.runId" size="small" type="text" :aria-label="`查看巡检运行 ${item.runId}`" @click="showRun(item.runId)">查看运行</a-button></div></article>
      <a-pagination v-if="state.total > 25" v-model:current="page" :total="state.total" :page-size="25" @change="load" />
    </template>
    <RunHistoryDrawer v-if="runId" v-model:visible="runVisible" :project-id="asset.projectId" :initial-run-id="runId" />
  </section>
</template>

<style scoped>
.schedule-panel { min-width: 0; overflow-wrap: anywhere; }.schedule-heading { display: flex; gap: 12px; align-items: start; justify-content: space-between; }h3 { font-size: 15px; margin: 0 0 12px; }.schedule-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding: 18px; background: #f8f9fd; border: 1px solid var(--border); border-radius: 8px; margin-block: 14px; }.schedule-form label { display: grid; gap: 7px; font-size: 12px; }.schedule-form input:not([type=checkbox]), .schedule-form select { box-sizing: border-box; width: 100%; min-width: 0; padding: 9px; color: var(--text); background: white; border: 1px solid var(--border); border-radius: 5px; font: inherit; }.schedule-form .schedule-enabled { grid-column: 1 / -1; display: flex; gap: 8px; align-items: center; }.schedule-help, .schedule-actions { grid-column: 1 / -1; }.schedule-actions { flex-wrap: wrap; }.schedule-success { color: #16815d; }.schedule-preview { border-left: 3px solid var(--primary); padding-left: 14px; font-size: 12px; margin-block: 20px; }.schedule-preview ol { padding-left: 18px; line-height: 2; }.schedule-summary { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; margin-block: 20px 10px; }.schedule-occurrence { border: 1px solid var(--border); border-radius: 7px; padding: 14px; margin-block: 12px; font-size: 12px; }.schedule-occurrence header, .schedule-record-footer { display: flex; align-items: center; flex-wrap: wrap; justify-content: space-between; gap: 10px; }.schedule-conflict { border-left: 3px solid #d78b27; padding: 12px; font-size: 12px; margin-block: 12px; }@media (max-width: 560px) { .schedule-form { grid-template-columns: minmax(0, 1fr); }.schedule-heading { flex-wrap: wrap; } }
</style>
