<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError, isRecord } from '../../api/client'
import { morningBriefApi, type MorningBriefForm, type MorningBriefOccurrence, type MorningBriefSchedule } from '../../api/morning-brief'
import type { Asset } from '../../api/types'
import { RequestScope } from '../../core/request-scope'
import { formatTime, jobLabels } from '../../core/format'
import ErrorNotice from '../common/ErrorNotice.vue'
import AssetDetailDrawer from '../assets/AssetDetailDrawer.vue'
import AiDrawer from '../ai/AiDrawer.vue'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ changed: [projectId: string] }>()
const saved = ref<MorningBriefSchedule>(), baseline = ref<MorningBriefSchedule>(), newer = ref<MorningBriefSchedule>()
const form = ref<MorningBriefForm>({ enabled: false, time: '08:00', timezone: 'Asia/Shanghai', instruction: '', maxRetries: 1 })
const editing = ref(false), loading = ref(false), saving = ref(false), rebasing = ref(false), conflict = ref(false)
const error = ref<unknown>(), historyError = ref<unknown>(), retryError = ref<unknown>(), message = ref(''), storageWarning = ref('')
const occurrences = ref<MorningBriefOccurrence[]>([]), total = ref(0), page = ref(1), historyLoading = ref(false)
const retrying = ref(''), pending = ref<Record<string, string>>({})
const readScope = new RequestScope(), historyScope = new RequestScope()
const detailVisible = ref(false), focusedId = ref(''), aiVisible = ref(false), aiTarget = ref<Asset>()
const detail = ref<InstanceType<typeof AssetDetailDrawer>>()
let timer: ReturnType<typeof setInterval> | undefined
let seenAssets = new Set<string>(), historyInitialized = false
const formOf = (value: MorningBriefForm): MorningBriefForm => ({ enabled: value.enabled, time: value.time, timezone: value.timezone, instruction: value.instruction, maxRetries: value.maxRetries })
const dirty = computed(() => baseline.value && JSON.stringify(form.value) !== JSON.stringify(formOf(baseline.value)))
const active = computed(() => occurrences.value.some(item => ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(item.status)))
const labels: Record<string, string> = { WAITING: '等待派发', SUBMITTED: '已派发', QUEUED: '排队中', RUNNING: '生成中', SUCCEEDED: '已生成', FAILED: '生成失败', CANCELLED: '已取消', INTERRUPTED: '已中断', RETRY_WAIT: '等待自动重试' }
const storageKey = (project: string) => `ai-test-platform:morning-retries:${project}`

function install(value: MorningBriefSchedule) { saved.value = value; baseline.value = value; form.value = formOf(value); newer.value = undefined; conflict.value = false }
async function loadSettings() {
  if (!props.projectId || saving.value || rebasing.value) return
  const token = readScope.begin(props.projectId); loading.value = true; error.value = undefined
  try {
    const value = await morningBriefApi.settings(token.key, token.signal)
    if (!readScope.isCurrent(token)) return
    saved.value = value
    if (!dirty.value) install(value)
    else if (baseline.value?.version !== value.version) newer.value = value
  } catch (failure) { if (readScope.isCurrent(token)) error.value = failure }
  finally { if (readScope.isCurrent(token)) loading.value = false }
}
async function loadHistory() {
  if (!props.projectId) return
  const token = historyScope.begin(props.projectId); historyLoading.value = true; historyError.value = undefined
  try {
    const value = await morningBriefApi.history(token.key, (page.value - 1) * 5, token.signal)
    if (!historyScope.isCurrent(token)) return
    occurrences.value = value.items; total.value = value.total
    const currentAssets = value.items.flatMap(item => item.assetId ? [item.assetId] : [])
    if (historyInitialized && currentAssets.some(id => !seenAssets.has(id))) emit('changed', token.key)
    currentAssets.forEach(id => seenAssets.add(id)); historyInitialized = true
  } catch (failure) { if (historyScope.isCurrent(token)) historyError.value = failure }
  finally { if (historyScope.isCurrent(token)) historyLoading.value = false }
}
function refresh() { void loadSettings(); void loadHistory() }
async function save() {
  if (!baseline.value || saving.value || rebasing.value) return
  const project = props.projectId, version = baseline.value.version, input = formOf(form.value)
  readScope.invalidate(); loading.value = false; saving.value = true; error.value = undefined; message.value = ''
  try {
    const value = await morningBriefApi.save(project, version, input)
    if (props.projectId !== project) return
    install(value); message.value = `晨报设置已保存 · v${value.version}`
  } catch (failure) {
    if (props.projectId === project) { error.value = failure; conflict.value = failure instanceof ApiError && failure.status === 409 }
  } finally { if (props.projectId === project) saving.value = false }
}
async function rebase() {
  if (!baseline.value || saving.value || rebasing.value) return
  const token = readScope.begin(props.projectId), previous = formOf(baseline.value), mine = formOf(form.value)
  rebasing.value = true; error.value = undefined
  try {
    const value = await morningBriefApi.settings(token.key, token.signal)
    if (!readScope.isCurrent(token)) return
    const merged = formOf(value)
    for (const key of Object.keys(previous) as (keyof MorningBriefForm)[]) if (mine[key] !== previous[key]) Object.assign(merged, { [key]: mine[key] })
    install(value); form.value = merged; message.value = `已基于 v${value.version} 保留我的修改，请检查后保存。`
  } catch (failure) { if (readScope.isCurrent(token)) error.value = failure }
  finally { if (readScope.isCurrent(token)) rebasing.value = false }
}
function persistPending(project = props.projectId) {
  try { localStorage.setItem(storageKey(project), JSON.stringify(pending.value)) }
  catch { storageWarning.value = '浏览器无法保存重试标识，请在离开页面前确认本次重试结果。' }
}
async function retry(id: string) {
  if (retrying.value) return
  const project = props.projectId, recovering = !!pending.value[id]
  const key = pending.value[id] ?? crypto.randomUUID()
  pending.value[id] = key; persistPending(project); retrying.value = id; retryError.value = undefined; message.value = ''
  try {
    await morningBriefApi.retry(project, id, key)
    if (project !== props.projectId) return
    delete pending.value[id]; persistPending(project)
    message.value = recovering ? '已确认上次重试任务，未重复创建。' : '已提交重试任务，沿用原统计快照。'
    await loadHistory()
  } catch (failure) {
    if (project === props.projectId) {
      retryError.value = failure
      if (failure instanceof ApiError && [404, 409, 422].includes(failure.status)) { delete pending.value[id]; persistPending(project) }
    }
  } finally { if (project === props.projectId) retrying.value = '' }
}
function openBrief(id: string) { focusedId.value = id; detailVisible.value = true }
function refine(asset: Asset) { if (asset.projectId === props.projectId) { aiTarget.value = asset; aiVisible.value = true } }
function changed(asset: Asset, updateDetail = true) {
  if (asset.projectId !== props.projectId) return
  if (aiTarget.value?.id === asset.id) aiTarget.value = asset
  if (updateDetail) detail.value?.applyAsset(asset)
  emit('changed', props.projectId)
}
watch(() => props.projectId, () => {
  readScope.invalidate(); historyScope.invalidate(); saved.value = undefined; baseline.value = undefined; newer.value = undefined
  editing.value = false; loading.value = false; saving.value = false; rebasing.value = false; conflict.value = false
  occurrences.value = []; total.value = 0; page.value = 1; historyLoading.value = false; seenAssets = new Set(); historyInitialized = false
  error.value = undefined; historyError.value = undefined; retryError.value = undefined; message.value = ''; storageWarning.value = ''; retrying.value = ''
  detailVisible.value = false; focusedId.value = ''; aiVisible.value = false; aiTarget.value = undefined; pending.value = {}
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(storageKey(props.projectId)) ?? '{}')
    if (isRecord(stored)) for (const [id, key] of Object.entries(stored).slice(0, 100)) if (/^[a-f0-9]{32}$/.test(id) && typeof key === 'string' && key.length > 0 && key.length <= 160) pending.value[id] = key
  } catch { /* Without browser persistence, current history and manual controls remain available. */ }
  refresh()
}, { immediate: true })
watch(page, () => { void loadHistory() })
onMounted(() => { timer = setInterval(() => { if ((saved.value?.enabled || active.value) && !historyLoading.value) void loadHistory() }, 3000) })
onUnmounted(() => { readScope.invalidate(); historyScope.invalidate(); clearInterval(timer) })
</script>

<template>
  <section aria-label="自动晨报" class="surface morning-panel">
    <div class="surface-heading"><div><h2>自动晨报</h2><p class="small muted">按项目时区汇总前一自然日，生成后仍可手工编辑和多轮调优。</p></div><div class="inline-actions"><a-button :loading="loading || historyLoading" @click="refresh">刷新晨报</a-button><a-button :disabled="!saved" :aria-expanded="editing" @click="editing = !editing">{{ editing ? '收起晨报设置' : '晨报设置' }}</a-button></div></div>
    <div class="morning-body">
      <ErrorNotice :error="error" retry @retry="loadSettings" />
      <p v-if="saved" class="small schedule-state">{{ saved.enabled ? `已启用 · 每日 ${saved.time} · ${saved.timezone}` : '尚未启用 · 可随时手工生成质量简报' }}<span v-if="saved.nextFireAt"> · 下次 {{ formatTime(saved.nextFireAt) }}（本机时间）</span></p>
      <a-alert v-if="saved && !saved.schedulerEnabled" type="warning">当前服务关闭了自动调度；已保存的启用设置将在调度恢复后生效。</a-alert>
      <p v-if="message" role="status" class="small operation-message">{{ message }}</p>
      <section v-if="editing && baseline" aria-label="晨报设置" class="morning-settings">
        <form @submit.prevent="save"><fieldset :disabled="saving || rebasing">
          <label class="enable-setting"><input v-model="form.enabled" type="checkbox">启用每日晨报</label>
          <div class="settings-grid"><label>当地时间<input v-model="form.time" type="time" required aria-label="当地时间"></label><label>晨报时区<input v-model="form.timezone" type="text" required aria-label="晨报时区" list="morning-timezones"><datalist id="morning-timezones"><option value="Asia/Shanghai" /><option value="UTC" /><option value="America/New_York" /><option value="Europe/London" /></datalist></label><label>自动重试上限<select v-model.number="form.maxRetries" aria-label="自动重试上限"><option v-for="value in [0, 1, 2, 3]" :key="value" :value="value">{{ value === 0 ? '不自动重试' : `${value} 次` }}</option></select></label><label class="instruction">生成要求<textarea v-model="form.instruction" aria-label="生成要求" rows="4" maxlength="16000" required /></label></div>
          <p class="small muted">停用会阻止新派发和自动重试，已经派发的任务继续完成。重试沿用该次任务首次保存的统计、要求与重试上限。</p>
          <p v-if="newer || conflict" class="small conflict-note">设置存在更新。当前输入已保留，可读取新版本后合并，再检查并保存。</p>
          <div class="inline-actions"><a-button type="primary" html-type="submit" :loading="saving">保存晨报设置</a-button><a-button v-if="newer || conflict" :loading="rebasing" @click="rebase">读取最新设置并保留我的修改</a-button></div>
        </fieldset></form>
      </section>
      <ErrorNotice :error="historyError" retry @retry="loadHistory" />
      <ErrorNotice :error="retryError" />
      <p v-if="storageWarning" class="small conflict-note">{{ storageWarning }}</p>
      <div v-if="Object.keys(pending).length" class="pending-retries"><div v-for="(key, id) in pending" :key="key"><span class="small">上次重试的确认尚未完成。</span><a-button :loading="retrying === id" :disabled="!!retrying && retrying !== id" @click="retry(String(id))">确认上次重试结果</a-button></div></div>
      <p v-if="!occurrences.length && !historyLoading && !historyError" class="small muted">尚无自动晨报记录。启用后将在下次约定时间生成。</p>
      <ol v-else class="morning-history" aria-label="晨报历史">
        <li v-for="item in occurrences" :key="item.id" :data-occurrence-id="item.id">
          <div class="history-heading"><strong>{{ item.localDate }} · {{ item.timezone }}</strong><a-tag size="small" :color="item.status === 'SUCCEEDED' ? 'green' : item.status === 'FAILED' ? 'red' : undefined">{{ labels[item.status] ?? item.status }}</a-tag></div>
          <p class="small muted">统计范围 {{ formatTime(item.windowFrom) }} 至 {{ formatTime(item.windowTo) }}（本机时间） · 已尝试 {{ item.attemptCount }} 次</p>
          <p v-if="item.missedFrom" class="small muted">从 {{ item.missedFrom }} 起错过的日期已合并到本次，没有逐日补跑。</p>
          <div class="inline-actions"><a-button v-if="item.assetId" @click="openBrief(item.assetId)">查看生成的简报</a-button><a-button v-else-if="['FAILED', 'CANCELLED', 'INTERRUPTED', 'RETRY_WAIT'].includes(item.status) && !pending[item.id]" :loading="retrying === item.id" :disabled="!!retrying" @click="retry(item.id)">手工重试此晨报</a-button></div>
          <details class="attempts"><summary>尝试记录与固定统计</summary><ul><li v-for="attempt in item.attempts" :key="attempt.jobId"><strong>第 {{ attempt.number }} 次 · {{ labels[attempt.status] ?? jobLabels[attempt.status] ?? attempt.status }}</strong> · {{ attempt.origin === 'MANUAL' ? '手工重试' : '自动派发' }}<p class="small muted">任务 {{ attempt.jobId }} · {{ formatTime(attempt.createdAt) }}</p><p v-if="attempt.error" class="attempt-error">{{ attempt.error }}</p><p v-if="attempt.nextRetryAt" class="small muted">记录的重试时间 {{ formatTime(attempt.nextRetryAt) }}；停用期间不派发。</p></li></ul><p class="small preserved-instruction">生成要求：{{ item.instruction }}</p><pre>{{ JSON.stringify(item.metrics, null, 2) }}</pre></details>
        </li>
      </ol>
      <a-pagination v-if="total > 5" v-model:current="page" :total="total" :page-size="5" simple show-total />
    </div>
    <AssetDetailDrawer ref="detail" v-model:visible="detailVisible" :project-id="projectId" :asset-id="focusedId" @changed="changed($event, false)" @removed="emit('changed', projectId)" @refine="refine" />
    <AiDrawer v-model:visible="aiVisible" :target="aiTarget" @applied="changed" />
  </section>
</template>

<style scoped>
.morning-panel { margin: 24px 0; min-width: 0; }.morning-body { padding: 18px 24px 24px; min-width: 0; }.surface-heading { flex-wrap: wrap; gap: 14px; }.surface-heading p { margin-bottom: 0; }.schedule-state { line-height: 1.8; }.operation-message { color: var(--primary, #5145e9); }.morning-settings { margin: 18px 0; padding: 18px; border: 1px solid var(--border); border-radius: 8px; background: var(--color-fill-1); }.morning-settings fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }.enable-setting { display: flex; align-items: center; gap: 8px; font-size: 13px; }.settings-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 18px 0; }.settings-grid label { display: grid; gap: 7px; font-size: 12px; min-width: 0; }.settings-grid input, .settings-grid select, .settings-grid textarea { min-width: 0; width: 100%; padding: 9px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--color-bg-2, white); color: var(--text); font: inherit; }.settings-grid textarea { resize: vertical; }.instruction { grid-column: 1 / -1; }.conflict-note, .attempt-error { color: rgb(var(--orange-6)); line-height: 1.8; }.morning-history { list-style: none; padding: 0; margin: 20px 0; }.morning-history > li { padding: 16px 0; border-top: 1px solid var(--border); min-width: 0; overflow-wrap: anywhere; }.history-heading { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 14px; }.attempts { font-size: 12px; margin-top: 14px; }.attempts summary { cursor: pointer; color: var(--muted); }.attempts ul { padding-left: 18px; }.attempts li { margin-block: 14px; }.attempts p { margin-block: 6px; }.attempts pre { max-height: 260px; overflow: auto; background: var(--color-fill-1); padding: 12px; border-radius: 6px; }.preserved-instruction { white-space: pre-wrap; overflow-wrap: anywhere; }.pending-retries > div { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin: 12px 0; }.inline-actions { flex-wrap: wrap; }
@media (max-width: 700px) { .settings-grid { grid-template-columns: 1fr; }.morning-body { padding: 16px; }.morning-settings { padding: 14px; } }
</style>
