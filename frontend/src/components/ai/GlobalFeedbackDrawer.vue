<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { aiApi } from '../../api/ai'
import { apiDiffApi } from '../../api/api-diffs'
import { assetApi } from '../../api/assets'
import { feedbackApi, type ChangeSet, type FeedbackRequest } from '../../api/feedback'
import { ApiError, isRecord } from '../../api/client'
import type { Asset, ConversationMessage, Job } from '../../api/types'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { readConversations, rememberConversation, rememberConversationScope, reserveConversationOrder, type ConversationReference } from '../../core/conversation-index'
import { jobLabels, formatTime } from '../../core/format'
import { jobFailure, terminalJobStates, useJobMonitor } from '../../composables/useJobMonitor'
import ChangeSetReview from './ChangeSetReview.vue'
import ConversationMessages from './ConversationMessages.vue'
import ErrorNotice from '../common/ErrorNotice.vue'
import SourceSnapshotSelect from '../analysis/SourceSnapshotSelect.vue'

const props = defineProps<{ projectId: string; assetIds?: string[]; pipelineId?: string; apiDiffId?: string; initialConversationId?: string; sourceSnapshotId?: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ applied: [assets: Asset[], deletedIds: string[]] }>()
const scope = new RequestScope(), historyScope = new RequestScope(), settlementScope = new RequestScope(), monitor = useJobMonitor()
const conversationId = ref(''), feedback = ref(''), notice = ref('')
const generationSourceId = ref('')
const references = ref<ConversationReference[]>([]), messages = ref<ConversationMessage[]>([])
const changes = ref<ChangeSet>(), selectedIds = ref<string[]>([])
const submitting = ref(false), historyLoading = ref(false), settlingJob = ref(''), applying = ref(false)
const scopeAssetIds = ref<string[]>()
const error = ref<unknown>()
const feedbackTargetKey = (input: { pipelineId?: string; apiDiffId?: string }) => input.apiDiffId ? `api-diff:${input.apiDiffId}` : input.pipelineId ? `pipeline:${input.pipelineId}` : 'global'
const targetKey = computed(() => feedbackTargetKey(props))
const drawerTitle = computed(() => props.apiDiffId ? '接口 AI 修复' : '全局 AI 反馈')
const busy = computed(() => submitting.value || historyLoading.value || !!settlingJob.value || applying.value || monitor.loading.value || monitor.active.value)
let current: ScopeToken | undefined, pending: FeedbackRequest | undefined, settledJob = '', pendingOrder = 0, pendingTime = ''
const initialSelection = () => props.assetIds?.length ? [...props.assetIds] : undefined
const sameSelection = (left?: string[], right?: string[]) => JSON.stringify(left?.slice().sort()) === JSON.stringify(right?.slice().sort())

watch(() => [visible.value, props.projectId, props.pipelineId, props.apiDiffId, props.initialConversationId, props.sourceSnapshotId], async () => {
  scope.invalidate(); historyScope.invalidate(); settlementScope.invalidate(); monitor.stop(); current = undefined
  messages.value = []; changes.value = undefined; selectedIds.value = []; references.value = []
  conversationId.value = props.initialConversationId ?? (props.pipelineId ? '' : crypto.randomUUID()); feedback.value = ''; notice.value = ''; error.value = undefined
  scopeAssetIds.value = initialSelection()
  generationSourceId.value = props.sourceSnapshotId ?? ''
  submitting.value = false; historyLoading.value = false; settlingJob.value = ''; applying.value = false; pending = undefined; settledJob = ''
  if (!visible.value || !props.projectId) return
  current = scope.begin(`${props.projectId}:${targetKey.value}`)
  references.value = readConversations(props.projectId, targetKey.value)
  const saved = references.value.find(entry => props.initialConversationId ? entry.id === props.initialConversationId : !scopeAssetIds.value || sameSelection(entry.assetIds, scopeAssetIds.value))
  if (saved) await selectConversation(saved.id)
  else if (props.initialConversationId) {
    const token = current
    historyLoading.value = true
    try { await loadMessages(token) } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
    finally { if (scope.isCurrent(token)) historyLoading.value = false }
  }
}, { immediate: true })

async function loadMessages(token: ScopeToken) {
  if (!conversationId.value) return
  const id = conversationId.value
  const query = historyScope.begin(id)
  try {
    const result = await aiApi.conversation(props.projectId, id, AbortSignal.any([token.signal, query.signal]))
    if (scope.isCurrent(token) && historyScope.isCurrent(query) && conversationId.value === id) messages.value = result.messages
  } catch (failure) { if (scope.isCurrent(token) && historyScope.isCurrent(query)) throw failure }
}
async function settle(job: Job) {
  const token = current
  if (!token || monitor.job.value?.id !== job.id || !terminalJobStates.has(job.status) || settledJob === job.id) return
  const round = settlementScope.begin(job.id)
  const ownsRound = () => scope.isCurrent(token) && settlementScope.isCurrent(round) && monitor.job.value?.id === job.id
  settledJob = job.id; settlingJob.value = job.id
  try {
    await loadMessages(token)
    if (!ownsRound()) return
    if (job.status === 'SUCCEEDED' && isRecord(job.result) && typeof job.result.changeSetId === 'string') {
      const result = await feedbackApi.changes(props.projectId, job.result.changeSetId, AbortSignal.any([token.signal, round.signal]))
      if (ownsRound()) { changes.value = result; selectedIds.value = []; error.value = undefined }
    } else if (job.status === 'SUCCEEDED' && isRecord(job.result) && job.result.status === 'NO_CHANGES') notice.value = String(job.result.message ?? '本轮无需修改已有资产')
    else if (job.status === 'FAILED' || job.status === 'INTERRUPTED') error.value = jobFailure(job)
    else if (job.status === 'CANCELLED') notice.value = '本轮生成已取消，现有资产保持不变'
  } catch (failure) { if (ownsRound()) { error.value = failure; settledJob = '' } }
  finally { if (ownsRound()) settlingJob.value = '' }
}
watch(monitor.job, job => { if (job) void settle(job) })
async function selectConversation(id: string) {
  if (busy.value || !current) return
  const token = scope.begin(`${props.projectId}:${targetKey.value}:${id}`); current = token
  monitor.stop(); settlementScope.invalidate(); settlingJob.value = ''; changes.value = undefined; selectedIds.value = []; settledJob = ''; error.value = undefined
  conversationId.value = id; historyLoading.value = true; pending = undefined
  try {
    const saved = references.value.find(entry => entry.id === id)
    generationSourceId.value = props.sourceSnapshotId || saved?.sourceSnapshotId || ''
    if (!props.pipelineId) {
      scopeAssetIds.value = saved?.assetIds ? [...saved.assetIds] : undefined
      if (scopeAssetIds.value) {
        const existing = await Promise.all(scopeAssetIds.value.map(async assetId => {
          try { await assetApi.get(props.projectId, assetId, token.signal); return assetId }
          catch (failure) { if (failure instanceof ApiError && failure.status === 404) return null; throw failure }
        }))
        if (!scope.isCurrent(token)) return
        scopeAssetIds.value = existing.filter((assetId): assetId is string => assetId !== null)
      }
    }
    await loadMessages(token)
    if (scope.isCurrent(token) && saved?.jobId) await monitor.start(props.projectId, saved.jobId)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) historyLoading.value = false }
}
function newConversation() {
  if (busy.value || props.pipelineId) return
  historyScope.invalidate(); settlementScope.invalidate(); current = scope.begin(`${props.projectId}:${targetKey.value}:new`); monitor.stop()
  conversationId.value = crypto.randomUUID(); messages.value = []; changes.value = undefined; selectedIds.value = []; scopeAssetIds.value = initialSelection()
  generationSourceId.value = props.sourceSnapshotId ?? ''
  historyLoading.value = false; settlingJob.value = ''
  settledJob = ''; pending = undefined; error.value = undefined; notice.value = ''
}
async function submit() {
  const text = feedback.value.trim()
  if (!current || busy.value || !text) return
  const identity = { projectId: props.projectId, pipelineId: props.pipelineId, apiDiffId: props.apiDiffId, conversationId: conversationId.value || undefined, assetIds: scopeAssetIds.value ? [...scopeAssetIds.value] : undefined, feedback: text, ...(!props.apiDiffId && generationSourceId.value ? { sourceSnapshotId: generationSourceId.value } : {}) }
  if (!pending || JSON.stringify({ ...pending, idempotencyKey: undefined }) !== JSON.stringify(identity)) {
    pending = { ...identity, idempotencyKey: crypto.randomUUID() }; pendingOrder = reserveConversationOrder(); pendingTime = new Date().toISOString()
  }
  const request = pending
  const submissionOrder = pendingOrder, submittedAt = pendingTime
  const token = scope.begin(`${props.projectId}:${targetKey.value}:${request.idempotencyKey}`); current = token
  historyScope.invalidate(); settlementScope.invalidate(); monitor.stop(); historyLoading.value = false; settlingJob.value = ''
  submitting.value = true; error.value = undefined; notice.value = ''; settledJob = ''; changes.value = undefined; selectedIds.value = []
  try {
    const result = request.apiDiffId ? await apiDiffApi.heal(request.projectId, request.apiDiffId, { instruction: request.feedback, conversationId: request.conversationId, idempotencyKey: request.idempotencyKey }) : await feedbackApi.submit(request)
    rememberConversation({ id: result.conversationId, projectId: request.projectId, targetKey: feedbackTargetKey(request), jobId: result.jobId, updatedAt: submittedAt, submissionOrder, assetIds: request.assetIds, sourceSnapshotId: request.sourceSnapshotId })
    if (!scope.isCurrent(token)) return
    pending = undefined; conversationId.value = result.conversationId
    references.value = readConversations(props.projectId, targetKey.value)
    await monitor.start(request.projectId, result.jobId)
    await loadMessages(token)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) submitting.value = false }
}
async function apply() {
  const token = current, target = changes.value, selection = [...selectedIds.value]
  if (!token || !target || !selection.length || busy.value) return
  applying.value = true; error.value = undefined; notice.value = ''
  try {
    const result = await feedbackApi.apply(props.projectId, target.id, selection, token.signal)
    if (!scope.isCurrent(token) || changes.value?.id !== target.id) return
    changes.value = { ...target, status: 'APPLIED' }; notice.value = `已采纳 ${selection.length} 项变更`
    const deletedIds = target.items.filter(item => selection.includes(item.id) && item.operation === 'DELETE' && item.targetId).map(item => item.targetId!)
    if (scopeAssetIds.value) {
      scopeAssetIds.value = [...new Set([...scopeAssetIds.value.filter(id => !deletedIds.includes(id)), ...result.assets.map(asset => asset.id)])]
      rememberConversationScope(props.projectId, targetKey.value, conversationId.value, monitor.job.value?.id ?? '', scopeAssetIds.value)
      references.value = readConversations(props.projectId, targetKey.value)
    }
    emit('applied', result.assets, deletedIds); await loadMessages(token)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) applying.value = false }
}
async function reject() {
  const token = current, target = changes.value
  if (!token || !target || busy.value) return
  applying.value = true; error.value = undefined
  try {
    await feedbackApi.reject(props.projectId, target.id, token.signal)
    if (!scope.isCurrent(token) || changes.value?.id !== target.id) return
    changes.value = { ...target, status: 'REJECTED' }; notice.value = '本轮候选已拒绝'; await loadMessages(token)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) applying.value = false }
}
onUnmounted(() => { scope.invalidate(); historyScope.invalidate(); settlementScope.invalidate(); monitor.stop() })
</script>

<template>
  <a-drawer v-model:visible="visible" :title="drawerTitle" role="dialog" :aria-label="drawerTitle" :width="980" :footer="false" unmount-on-close>
    <p class="small muted">{{ apiDiffId ? '围绕已采纳接口变更的受影响资产' : pipelineId ? '围绕当前流水线' : scopeAssetIds ? `围绕当前限定范围的 ${scopeAssetIds.length} 条资产` : '围绕当前项目全部资产' }}连续提出改进意见。已确认资产受到保护，每轮候选由你选择采纳。</p>
    <p v-if="scopeAssetIds?.length === 0" class="small muted">此会话中选定的资产已全部删除，可以继续提出新增要求。</p>
    <SourceSnapshotSelect v-if="!apiDiffId" v-model="generationSourceId" :project-id="projectId" :disabled="busy" :locked="!!sourceSnapshotId" empty-label="沿用资产来源；新增时继承唯一来源" />
    <p v-if="!apiDiffId && !sourceSnapshotId" class="small muted">现有资产保留各自的固定来源；涉及多个来源时，为新增资产选择本轮使用的快照。</p>
    <div class="feedback-history"><a-select v-if="references.length" :model-value="conversationId" :options="references.map(entry => ({ value: entry.id, label: `${formatTime(entry.updatedAt)} · ${entry.id.slice(0, 8)}` }))" :disabled="busy || !!pipelineId" aria-label="反馈会话" placeholder="选择会话" @change="selectConversation(String($event))" /><a-button v-if="!pipelineId" :disabled="busy" @click="newConversation">新建会话</a-button></div>
    <ErrorNotice :error="error ?? monitor.error.value" style="margin-block: 16px" />
    <a-alert v-if="notice" type="success" style="margin-block: 16px">{{ notice }}</a-alert>
    <div v-if="monitor.job.value" class="feedback-progress"><a-tag>{{ jobLabels[monitor.job.value.status] }}</a-tag><span class="small">{{ monitor.job.value.message }}</span><a-button v-if="monitor.active.value" size="small" @click="monitor.cancel">取消本轮生成</a-button></div>
    <p v-if="monitor.reconnecting.value" class="small muted">连接暂时中断，正在读取任务的持久化状态…</p>
    <ChangeSetReview v-if="changes" v-model:selected-ids="selectedIds" :change-set="changes" :busy="busy" @apply="apply" @reject="reject" />
    <details class="feedback-messages" :open="!changes"><summary>多轮反馈记录 · {{ messages.filter(message => message.role === 'user').length }} 轮</summary><ConversationMessages :messages="messages" :answering="monitor.active.value" :streamed-text="monitor.streamedText.value" @reuse="feedback = $event" /></details>
    <div class="feedback-compose"><label :for="`feedback-input-${targetKey}`">本轮修改意见</label><a-textarea v-model="feedback" :max-length="16000" :auto-size="{ minRows: 3, maxRows: 8 }" :textarea-attrs="{ id: `feedback-input-${targetKey}`, 'aria-label': apiDiffId ? '接口修复意见' : '全局反馈意见' }" :placeholder="apiDiffId ? '例如：将请求的 userId 改成 uid，保留人工值与现有断言。' : '例如：补充优惠券部分退款的异常用例，保持现有支付场景和已确认内容。'" :disabled="busy" /><div class="inline-actions"><a-button type="primary" :disabled="!feedback.trim() || busy" :loading="submitting" @click="submit">生成改进候选</a-button><span class="small muted">可以继续多轮反馈，每轮使用当前已保存的资产。</span></div></div>
  </a-drawer>
</template>

<style scoped>
.feedback-history, .feedback-progress { display: flex; align-items: center; gap: 12px; margin: 16px 0; }
.feedback-history .arco-select { max-width: 330px; }
.feedback-messages { margin: 24px 0; border-top: 1px solid var(--border); padding-top: 16px; }
.feedback-messages :deep(.conversation-messages) { max-height: 450px; }
.feedback-compose { display: grid; gap: 12px; padding: 20px 0; position: sticky; bottom: -16px; background: var(--color-bg-2); border-top: 1px solid var(--border); }
.feedback-compose label { font-weight: 600; font-size: 13px; }
</style>
