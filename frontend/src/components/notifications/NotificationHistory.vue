<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { assetApi } from '../../api/assets'
import { ApiError, isRecord } from '../../api/client'
import { notificationApi, type NotificationDelivery, type NotificationRetry } from '../../api/notifications'
import { runLabels } from '../../api/runs'
import type { Asset } from '../../api/types'
import { formatTime, jobLabels } from '../../core/format'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import EmptyState from '../common/EmptyState.vue'
import RunHistoryDrawer from '../execution/RunHistoryDrawer.vue'

const props = defineProps<{ asset: Asset }>()
const emit = defineEmits<{ changed: [asset: Asset] }>()
const items = ref<NotificationDelivery[]>([]), total = ref(0), page = ref(1), senderEnabled = ref(true)
const loading = ref(false), busy = ref(''), refreshingConfig = ref(false), error = ref<unknown>(), retryError = ref<unknown>()
const message = ref(''), storageWarning = ref(''), pending = ref<Record<string, NotificationRetry>>({})
const acknowledged = ref<Record<string, boolean>>({}), expanded = ref<Record<string, boolean>>({})
const runVisible = ref(false), runId = ref(''), scope = new RequestScope(), configScope = new RequestScope()
let timer: ReturnType<typeof setInterval> | undefined, generation = 0
const active = computed(() => items.value.some(item => ['SUBMITTED', 'SENDING', 'RETRY_WAIT'].includes(item.status)))
const identity = () => `${props.asset.projectId}:${props.asset.id}`
const storageKey = (key: string) => `ai-test-platform:notification-retries:${key}`
const labels: Record<string, string> = { WAITING: '等待派发', SUBMITTED: '排队中', QUEUED: '排队中', SENDING: '发送中', DELIVERED: '已送达', REJECTED: '接收端拒绝', FAILED: '发送失败', UNCERTAIN: '送达状态未知', RETRY_WAIT: '等待自动重试', SKIPPED_CONDITION: '按条件跳过', BLOCKED_CONFIG: '配置已变更，未发送', NOT_SENT: '未发送', CANCELLED: '发送前已取消', INTERRUPTED: '发送前已中断' }
const diagnostics: Record<string, string> = { HTTP_REJECTED: '接收端返回了非成功 HTTP 状态', VENDOR_REJECTED: '机器人明确拒绝了消息，请检查平台设置', INVALID_ACKNOWLEDGEMENT: '接收端响应无法解析，可能已送达', MISSING_ACKNOWLEDGEMENT: '接收端没有返回明确成功码', CONFLICTING_ACKNOWLEDGEMENT: '接收端返回了相互冲突的状态', CONNECT_FAILED: '连接尚未建立', DELIVERY_NOT_CONFIRMED: '网络、响应上限或取消导致无法确认送达', DESTINATION_CHANGED: '发送前的目标配置已变更或停用', SEND_INTERRUPTED: '发送期间任务中断，可能已送达', JOB_STOPPED_BEFORE_SEND: '任务在开始发送前停止' }
function canRetry(item: NotificationDelivery) { return ['FAILED', 'UNCERTAIN', 'BLOCKED_CONFIG', 'CANCELLED', 'INTERRUPTED'].includes(item.status) }
async function load() {
  const target = props.asset, token = scope.begin(identity()); loading.value = true; error.value = undefined
  try {
    const result = await notificationApi.history(target.projectId, target.id, (page.value - 1) * 10, token.signal)
    if (!scope.isCurrent(token)) return
    items.value = result.items; total.value = result.total; senderEnabled.value = result.senderEnabled
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
function persist(key: string) {
  try { localStorage.setItem(storageKey(key), JSON.stringify(pending.value)) }
  catch { storageWarning.value = '浏览器无法保存重试标识，请在离开此页前确认本次重试结果。' }
}
async function retry(id: string) {
  if (busy.value) return
  const target = props.asset, key = identity(), epoch = generation, recovering = !!pending.value[id]
  const request = pending.value[id] ?? { baseVersion: target.version, idempotencyKey: crypto.randomUUID(), acknowledgeUncertain: acknowledged.value[id] === true }
  pending.value[id] = request; persist(key); busy.value = id; retryError.value = undefined; message.value = ''
  try {
    await notificationApi.retry(target.projectId, target.id, id, request)
    if (generation !== epoch || key !== identity()) return
    delete pending.value[id]; persist(key)
    message.value = recovering ? '已确认上次重试任务，没有再次派发。' : '已提交重试，沿用原运行报告。'
    await load()
  } catch (failure) {
    if (generation !== epoch || key !== identity()) return
    retryError.value = failure
    if (failure instanceof ApiError && [400, 404, 409, 422].includes(failure.status)) { delete pending.value[id]; persist(key) }
  } finally { if (generation === epoch && key === identity()) busy.value = '' }
}
async function refreshConfig() {
  const target = props.asset, token = configScope.begin(identity()); refreshingConfig.value = true; retryError.value = undefined
  try {
    const updated = await assetApi.get(target.projectId, target.id, token.signal)
    if (configScope.isCurrent(token)) { emit('changed', updated); message.value = `已读取通知配置 v${updated.version}，请核对后再重试。` }
  } catch (failure) { if (configScope.isCurrent(token)) retryError.value = failure }
  finally { if (configScope.isCurrent(token)) refreshingConfig.value = false }
}
function openRun(id: string) { runId.value = id; runVisible.value = true }
watch(() => [props.asset.projectId, props.asset.id], () => {
  generation++; scope.invalidate(); configScope.invalidate(); items.value = []; total.value = 0; page.value = 1; loading.value = false; busy.value = ''
  error.value = undefined; retryError.value = undefined; message.value = ''; storageWarning.value = ''; pending.value = {}; acknowledged.value = {}; expanded.value = {}
  runVisible.value = false; runId.value = ''; refreshingConfig.value = false
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(storageKey(identity())) ?? '{}')
    if (isRecord(stored)) for (const [id, value] of Object.entries(stored).slice(0, 100)) {
      if (/^[a-f0-9]{32}$/.test(id) && isRecord(value) && typeof value.baseVersion === 'string' && /^[1-9][0-9]{0,18}$/.test(value.baseVersion)
          && typeof value.idempotencyKey === 'string' && value.idempotencyKey.length > 0 && value.idempotencyKey.length <= 160 && typeof value.acknowledgeUncertain === 'boolean')
        pending.value[id] = { baseVersion: value.baseVersion, idempotencyKey: value.idempotencyKey, acknowledgeUncertain: value.acknowledgeUncertain }
    }
  } catch { /* Malformed browser state cannot manufacture a retry; the history remains usable. */ }
  void load()
}, { immediate: true })
watch(page, () => { void load() })
onMounted(() => { timer = setInterval(() => { if ((props.asset.data.enabled || active.value) && !loading.value) void load() }, 3000) })
onUnmounted(() => { generation++; scope.invalidate(); configScope.invalidate(); clearInterval(timer) })
</script>

<template>
  <section role="region" aria-label="群通知发送记录" class="notification-history">
    <div class="notification-heading"><h3>发送记录</h3><a-button :loading="loading" @click="load">刷新发送记录</a-button></div>
    <p class="small config-state">{{ asset.data.enabled ? '已启用' : '尚未启用' }} · {{ asset.data.platform }} · 当前配置 v{{ asset.version }} · {{ asset.data.failOnly ? '仅失败、阻塞和中断通知' : '通知全部完成结果' }}</p>
    <p class="small muted">启用后只处理新的完成结果。每条通知保留固定报告；修改配置会阻止尚未开始的旧任务。发送中的请求仍会记录结果。</p>
    <a-alert v-if="!senderEnabled" type="warning">当前服务已关闭消息发送。</a-alert>
    <ErrorNotice :error="error" retry @retry="load" /><ErrorNotice :error="retryError" />
    <a-button v-if="retryError instanceof ApiError && retryError.status === 409" :loading="refreshingConfig" @click="refreshConfig">读取最新通知配置</a-button>
    <p v-if="message" role="status" class="small operation-message">{{ message }}</p>
    <p v-if="storageWarning" class="small warning">{{ storageWarning }}</p>
    <div v-for="(request, id) in pending" :key="request.idempotencyKey" class="pending-retry"><p class="small">上次重试结果尚未确认。</p><a-button :loading="busy === id" :disabled="!!busy && busy !== id" @click="retry(String(id))">确认上次重试结果</a-button></div>
    <EmptyState v-if="!items.length && !loading && !error" title="暂无发送记录" description="人工启用后，新的运行完成结果会按通知条件显示在这里。" />
    <ol v-else class="delivery-list" aria-label="通知历史">
      <li v-for="item in items" :key="item.id" :data-delivery-id="item.id">
        <div class="notification-heading"><strong>{{ item.report.planName }}</strong><a-tag :color="item.status === 'DELIVERED' ? 'green' : item.status === 'UNCERTAIN' ? 'orange' : item.status === 'FAILED' ? 'red' : undefined" data-testid="notification-status">{{ labels[item.status] ?? item.status }}</a-tag></div>
        <p class="small">运行结果：{{ runLabels[item.report.status] ?? item.report.status }} · 执行项 {{ item.report.summary.total }} · 用例 {{ item.report.summary.caseCount }} · 数据行 {{ item.report.summary.dataRows }}</p>
        <p class="small muted">{{ formatTime(item.report.completedAt) }} · 已尝试 {{ item.attemptCount }} 次 · 自动重试 {{ item.automaticRetries }}/{{ item.maxRetries }}</p>
        <p v-if="item.nextRetryAt" class="small muted">下次自动重试：{{ formatTime(item.nextRetryAt) }}，以当前启用配置仍有效为前提。</p>
        <div v-if="item.status === 'UNCERTAIN'" class="uncertain-note"><p>上次消息可能已经送达，请先检查群消息。此状态不会自动重发。</p><label><input v-model="acknowledged[item.id]" type="checkbox">已检查群消息，接受可能重复发送</label></div>
        <div class="inline-actions"><a-button @click="openRun(item.runId)">查看来源运行</a-button><a-button v-if="canRetry(item) && !pending[item.id]" :loading="busy === item.id" :disabled="!!busy || !senderEnabled || !asset.data.enabled || (item.status === 'UNCERTAIN' && !acknowledged[item.id])" @click="retry(item.id)">使用当前配置重试</a-button><a-button :aria-expanded="expanded[item.id] === true" @click="expanded[item.id] = !expanded[item.id]">{{ expanded[item.id] ? '收起发送尝试' : '展开发送尝试' }}</a-button></div>
        <ul v-if="expanded[item.id]" class="attempt-list" aria-label="发送尝试"><li v-for="attempt in item.attempts" :key="attempt.id"><strong>第 {{ attempt.number }} 次 · {{ labels[attempt.outcome] ?? attempt.outcome }}</strong><p class="small muted">{{ attempt.origin === 'MANUAL' ? '手工重试' : '自动派发' }} · 配置 v{{ attempt.configVersion }} · {{ formatTime(attempt.createdAt) }}</p><p v-if="attempt.diagnosticCode" class="small warning">{{ diagnostics[attempt.diagnosticCode] ?? '本次发送未完成' }}{{ attempt.httpStatus ? ` · HTTP ${attempt.httpStatus}` : '' }}</p><p class="small muted">任务：{{ jobLabels[attempt.jobStatus] ?? attempt.jobStatus }} · <span class="mono">{{ attempt.jobId }}</span></p></li></ul>
      </li>
    </ol>
    <a-pagination v-if="total > 10" v-model:current="page" :total="total" :page-size="10" simple show-total />
    <RunHistoryDrawer v-model:visible="runVisible" :project-id="asset.projectId" :initial-run-id="runId" />
  </section>
</template>

<style scoped>
.notification-history { min-width: 0; }.notification-heading { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }.notification-heading h3 { margin: 0; font-size: 17px; }.config-state { line-height: 1.8; }.delivery-list { list-style: none; padding: 0; margin: 20px 0; }.delivery-list > li { border-top: 1px solid var(--border); padding-block: 20px; min-width: 0; overflow-wrap: anywhere; }.delivery-list p { line-height: 1.8; }.inline-actions { flex-wrap: wrap; gap: 8px; }.uncertain-note { padding: 12px 16px; border-radius: 8px; background: var(--color-warning-light-1); margin-bottom: 16px; font-size: 13px; }.uncertain-note p { margin-top: 0; }.uncertain-note label { display: flex; align-items: flex-start; gap: 8px; line-height: 1.6; }.uncertain-note input { margin-top: 4px; flex-shrink: 0; }.attempt-list { margin-top: 18px; padding-left: 20px; font-size: 13px; }.attempt-list li { margin-block: 18px; }.attempt-list p { margin-block: 6px; }.warning { color: rgb(var(--orange-6)); }.operation-message { color: var(--primary, #5145e9); }.pending-retry { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-block: 14px; }
</style>
