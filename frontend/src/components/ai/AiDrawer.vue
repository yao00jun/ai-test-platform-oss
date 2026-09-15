<script setup lang="ts">
/*
 * Conversation selection, feedback reuse and drawer composition adapted from
 * MeterSphere ms-ai-drawer/index.vue and components/conversationList.vue.
 * Copyright (c) 2026-present FIT2CLOUD. See THIRD_PARTY_NOTICES.md.
 * Project-scoped persistent conversations and native SSE replace the original
 * organization APIs/global Axios cancellation. Closing never cancels a server job.
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { IconHistory, IconPlus, IconSend, IconStop } from '@arco-design/web-vue/es/icon'
import { aiApi } from '../../api/ai'
import { assetApi } from '../../api/assets'
import { ApiError, isRecord } from '../../api/client'
import { JobStream, type JobEvent } from '../../api/job-stream'
import type { Asset, ConversationMessage, GenerationScope, Job } from '../../api/types'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { readConversations, rememberConversation, reserveConversationOrder, type ConversationReference } from '../../core/conversation-index'
import { formatTime, jobLabels } from '../../core/format'
import ErrorNotice from '../common/ErrorNotice.vue'
import ConversationMessages from './ConversationMessages.vue'
import SourceSnapshotSelect from '../analysis/SourceSnapshotSelect.vue'
import GenerationEvidencePanel from '../analysis/GenerationEvidencePanel.vue'

const props = defineProps<{ target?: Asset; generation?: GenerationScope; targetFields?: string[]; scopeLabel?: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ applied: [asset: Asset]; generated: [assets: Asset[], restored: boolean] }>()
const projectId = computed(() => props.target?.projectId ?? props.generation?.projectId ?? '')
const targetFields = computed(() => props.targetFields?.length ? [...new Set(props.targetFields)].sort() : undefined)
const targetKey = computed(() => props.target ? `${props.target.id}${targetFields.value ? `:fields:${targetFields.value.join(',')}` : ''}` : `generate:${props.generation?.type}:${props.generation?.parentId ?? 'ROOT'}${props.generation?.runId ? `:run:${props.generation.runId}` : ''}`)
const localTarget = ref<Asset>()
const sourceSnapshotId = ref('')
const supportsSource = computed(() => !!props.generation && ['REQUIREMENT', 'FUNCTIONAL_CASE', 'FUNCTIONAL_STEP', 'API_DEFINITION', 'API_CASE', 'SCENARIO', 'SCENARIO_STEP', 'SQL_VALIDATION', 'UI_SCENARIO', 'UI_STEP', 'TEST_PLAN', 'PLAN_ITEM', 'BUG'].includes(props.generation.type))
const conversationId = ref('')
const references = ref<ConversationReference[]>([])
const messages = ref<ConversationMessage[]>([])
const feedback = ref('')
const streamedText = ref('')
const busy = ref(false)
const historyLoading = ref(false)
const historyOpen = ref(false)
const error = ref<unknown>()
const connectionMessage = ref('')
const successMessage = ref('')
const job = ref<Job>()
const jobId = ref('')
const progress = ref(0)
const progressMessage = ref('')
const scope = new RequestScope()
const conversationScope = new RequestScope()
const stream = new JobStream()
let session: ScopeToken | undefined
let settlingKey = ''
let pending: { identity: string; key: string; order: number; time: string } | undefined
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'])

watch(() => [visible.value, projectId.value, targetKey.value], async () => {
  stream.close()
  scope.invalidate()
  conversationScope.invalidate()
  settlingKey = ''
  pending = undefined
  busy.value = false
  error.value = undefined
  connectionMessage.value = ''
  successMessage.value = ''
  streamedText.value = ''
  feedback.value = ''
  messages.value = []
  conversationId.value = crypto.randomUUID()
  jobId.value = ''
  job.value = undefined
  progress.value = 0
  progressMessage.value = ''
  historyOpen.value = false
  historyLoading.value = false
  localTarget.value = props.target
  sourceSnapshotId.value = props.generation?.sourceSnapshotId ?? ''
  if (!visible.value || !projectId.value) return
  session = scope.begin(`${projectId.value}:${targetKey.value}`)
  references.value = readConversations(projectId.value, targetKey.value)
  if (references.value[0]) await selectConversation(references.value[0])
}, { immediate: true })
watch(() => props.target, (target) => { if (target?.id === localTarget.value?.id && !busy.value) localTarget.value = target })

async function loadConversation(current: ScopeToken) {
  const id = conversationId.value
  if (!id) return
  const request = conversationScope.begin(id)
  historyLoading.value = true
  try {
    const result = await aiApi.conversation(projectId.value, id, AbortSignal.any([current.signal, request.signal]))
    if (scope.isCurrent(current) && conversationScope.isCurrent(request) && conversationId.value === id) messages.value = result.messages
  } catch (failure) { if (scope.isCurrent(current) && conversationScope.isCurrent(request)) error.value = failure }
  finally { if (scope.isCurrent(current) && conversationScope.isCurrent(request)) historyLoading.value = false }
}
async function selectConversation(reference: ConversationReference) {
  const current = session
  if (!current || busy.value) return
  pending = undefined
  conversationId.value = reference.id
  if (!props.target) sourceSnapshotId.value = props.generation?.sourceSnapshotId || reference.sourceSnapshotId || ''
  jobId.value = ''
  historyOpen.value = false
  error.value = undefined
  await loadConversation(current)
  if (!scope.isCurrent(current) || conversationId.value !== reference.id || !reference.jobId) return
  try {
    const result = await aiApi.job(projectId.value, reference.jobId, current.signal)
    if (!scope.isCurrent(current) || conversationId.value !== reference.id) return
    job.value = result
    jobId.value = result.id
    if (!terminal.has(result.status)) { busy.value = true; subscribe(current, result.id) }
    else { busy.value = true; await settle(current, result.id, true) }
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
}
function newConversation() {
  if (busy.value) return
  stream.close()
  conversationScope.invalidate()
  historyLoading.value = false
  pending = undefined
  conversationId.value = crypto.randomUUID()
  sourceSnapshotId.value = props.generation?.sourceSnapshotId ?? ''
  messages.value = []
  job.value = undefined
  jobId.value = ''
  error.value = undefined
  successMessage.value = ''
  historyOpen.value = false
}
function eventData(event: JobEvent): Record<string, unknown> {
  return isRecord(event.data.payload) ? { ...event.data, ...event.data.payload } : isRecord(event.data.data) ? { ...event.data, ...event.data.data } : event.data
}
function subscribe(current: ScopeToken, id: string) {
  stream.subscribe({ projectId: projectId.value, jobId: id }, (event) => {
    if (!scope.isCurrent(current) || jobId.value !== id) return
    connectionMessage.value = ''
    const data = eventData(event)
    if (event.type === 'token') {
      const text = data.token ?? data.text ?? data.content
      if (typeof text === 'string') streamedText.value = (streamedText.value + text).slice(-100000)
    }
    if (typeof data.progress === 'number') progress.value = Math.max(0, Math.min(1, data.progress / 100))
    if (typeof data.message === 'string') progressMessage.value = data.message
    if (event.type === 'done') void settle(current, id)
    if (event.type === 'error') { if (typeof data.message === 'string') error.value = new ApiError(422, String(data.code ?? 'JOB_FAILED'), data.message, data.details); void settle(current, id) }
  }, () => {
    if (!scope.isCurrent(current)) return
    connectionMessage.value = '连接暂时中断，正在自动重连…'
    void settle(current, id)
  })
}
async function settle(current: ScopeToken, id: string, restored = false) {
  const key = `${current.sequence}:${id}`
  if (settlingKey === key || !scope.isCurrent(current) || jobId.value !== id) return
  settlingKey = key
  let completed = false
  try {
    const result = await aiApi.job(projectId.value, id, current.signal)
    if (!scope.isCurrent(current) || jobId.value !== id) return
    job.value = result
    if (!terminal.has(result.status)) return
    completed = true
    stream.close()
    connectionMessage.value = ''
    streamedText.value = ''
    await loadConversation(current)
    if (!scope.isCurrent(current) || jobId.value !== id) return
    if (result.status === 'SUCCEEDED') {
      const target = localTarget.value
      if (target) {
        const updated = await assetApi.get(target.projectId, target.id, current.signal)
        if (!scope.isCurrent(current) || jobId.value !== id || updated.id !== target.id || updated.projectId !== target.projectId) return
        localTarget.value = updated
        successMessage.value = `已更新「${updated.name}」至版本 ${updated.version}，可以继续输入下一轮反馈。`
        emit('applied', updated)
      } else {
        const assets = isRecord(result.result) && Array.isArray(result.result.assets) ? result.result.assets.filter((asset): asset is Asset => isRecord(asset) && asset.projectId === projectId.value && typeof asset.id === 'string' && typeof asset.type === 'string' && typeof asset.version === 'string' && isRecord(asset.data)) : []
        successMessage.value = '生成已完成，测试资产列表已刷新。'
        emit('generated', assets, restored)
      }
    } else if (result.status === 'FAILED' || result.status === 'INTERRUPTED') {
      const detail = isRecord(result.error) ? result.error : {}
      error.value = new ApiError(422, String(detail.code ?? result.status), String(detail.message ?? result.error ?? result.message ?? '任务未完成'), detail.details)
    }
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
  finally {
    if (settlingKey === key) settlingKey = ''
    if (completed && scope.isCurrent(current) && jobId.value === id) busy.value = false
  }
}
async function submit() {
  const current = session
  const text = feedback.value.trim()
  const target = localTarget.value
  const generation = props.generation
  if (!current || !text || busy.value || (!target && !generation)) return
  const referenceProject = projectId.value
  const referenceTarget = targetKey.value
  const submittedConversation = conversationId.value
  const submittedSource = target || !supportsSource.value ? undefined : sourceSnapshotId.value || undefined
  const identity = JSON.stringify({ projectId: referenceProject, targetKey: referenceTarget, baseVersion: target?.version, targetFields: targetFields.value, text, conversationId: submittedConversation, sourceSnapshotId: target ? undefined : sourceSnapshotId.value })
  if (pending?.identity !== identity) pending = { identity, key: crypto.randomUUID(), order: reserveConversationOrder(), time: new Date().toISOString() }
  const request = pending
  const submissionOrder = request.order, submittedAt = request.time
  stream.close()
  jobId.value = ''
  job.value = undefined
  busy.value = true
  error.value = undefined
  successMessage.value = ''
  streamedText.value = ''
  progress.value = 0
  progressMessage.value = ''
  try {
    const accepted = target ? await aiApi.refine(target, text, submittedConversation, request.key, targetFields.value) : await aiApi.generate({ projectId: generation!.projectId, type: generation!.type, parentId: generation!.parentId, runId: generation!.runId, ...(supportsSource.value && sourceSnapshotId.value ? { sourceSnapshotId: sourceSnapshotId.value } : {}), instruction: text, conversationId: submittedConversation, idempotencyKey: request.key })
    rememberConversation({ id: accepted.conversationId, jobId: accepted.jobId, projectId: referenceProject, targetKey: referenceTarget, updatedAt: submittedAt, submissionOrder, sourceSnapshotId: submittedSource })
    if (!scope.isCurrent(current)) return
    pending = undefined
    conversationId.value = accepted.conversationId
    jobId.value = accepted.jobId
    job.value = undefined
    feedback.value = ''
    references.value = readConversations(referenceProject, referenceTarget)
    subscribe(current, accepted.jobId)
    await loadConversation(current)
    if (scope.isCurrent(current)) void settle(current, accepted.jobId)
  } catch (failure) { if (scope.isCurrent(current)) { error.value = failure; busy.value = false } }
}
async function cancel() {
  const current = session
  const id = jobId.value
  if (!current || !id) return
  try {
    const result = await aiApi.cancel(projectId.value, id)
    if (!scope.isCurrent(current) || jobId.value !== id) return
    job.value = result
    await settle(current, id)
  } catch (failure) { if (scope.isCurrent(current)) error.value = failure }
}
onUnmounted(() => { stream.close(); scope.invalidate(); conversationScope.invalidate() })
</script>

<template>
  <a-drawer v-model:visible="visible" :title="target ? 'AI 优化' : 'AI 生成'" role="dialog" aria-modal="true" :aria-label="target ? 'AI 优化' : 'AI 生成'" :width="660" :footer="false" class="ai-drawer" unmount-on-close>
    <div class="ai-drawer-inner">
      <div class="ai-context"><strong>{{ localTarget?.name ?? generation?.label }}</strong><span v-if="localTarget" class="small muted">版本 {{ localTarget.version }}</span></div>
      <p class="ai-policy">{{ target ? '通过校验后直接更新当前记录，历史版本可随时查看和恢复。' : '描述测试目标与约束，生成结果会保存到当前项目。' }}</p>
      <p v-if="target && scopeLabel" class="ai-policy">本轮只调优：{{ scopeLabel }}。</p>
      <details v-if="localTarget?.data.sourceSnapshotId" class="ai-source-details"><summary>当前资产的固定生成依据</summary><GenerationEvidencePanel :asset="localTarget" /></details>
      <SourceSnapshotSelect v-else-if="!target && supportsSource" v-model="sourceSnapshotId" :project-id="projectId" :disabled="busy" style="margin-bottom: 16px" />
      <p v-if="!target && generation?.type === 'QUALITY_BRIEF'" class="ai-policy">{{ generation.runId ? '统计范围：当前运行。' : '统计范围：当前项目最近 24 小时内创建的运行。' }}统计由实际运行记录计算并随简报保存，AI 生成的分析正文可继续编辑和调优。</p>
      <div class="conversation-toolbar"><a-button type="text" size="small" :disabled="busy" @click="historyOpen = !historyOpen"><template #icon><IconHistory /></template>会话历史<span v-if="references.length"> · {{ references.length }}</span></a-button><a-button type="text" size="small" :disabled="busy" @click="newConversation"><template #icon><IconPlus /></template>新会话</a-button></div>
      <div v-if="historyOpen" class="conversation-history"><p v-if="!references.length" class="small muted">此浏览器还没有记录当前资产的会话。</p><button v-for="reference in references" :key="reference.id" type="button" :class="{ active: reference.id === conversationId }" @click="selectConversation(reference)"><span>{{ formatTime(reference.updatedAt) }}</span><span class="mono">{{ reference.id.slice(0, 10) }}</span></button></div>
      <div v-if="historyLoading" class="small muted" style="padding: 8px 0"><a-spin :size="12" /> 加载服务端会话…</div>
      <ConversationMessages :messages="messages" :answering="busy" :streamed-text="streamedText" @reuse="feedback = $event" />
      <div v-if="busy || job" class="job-progress"><div class="inline-actions small"><a-tag :color="job?.status === 'SUCCEEDED' ? 'green' : 'arcoblue'" size="small">{{ job ? jobLabels[job.status] : '任务已提交' }}</a-tag><span class="muted">{{ progressMessage || job?.message }}</span></div><a-progress v-if="busy" :percent="progress" size="small" :show-text="false" /><p v-if="connectionMessage" class="small muted">{{ connectionMessage }}</p></div>
      <ErrorNotice :error="error" style="margin-bottom: 12px" />
      <a-alert v-if="successMessage" type="success" style="margin-bottom: 12px">{{ successMessage }}</a-alert>
      <div class="feedback-composer"><label for="ai-feedback">{{ target ? '本轮反馈' : '生成要求' }}</label><a-textarea v-model="feedback" :textarea-attrs="{ id: 'ai-feedback', 'aria-label': target ? '本轮反馈' : '生成要求' }" :disabled="busy" :auto-size="{ minRows: 3, maxRows: 7 }" :max-length="16000" :placeholder="target ? '例如：补充空值和超时场景，并明确每一步的预期结果。' : '描述需要生成的测试内容、关键流程和边界条件。'" @keydown.ctrl.enter.prevent="submit" /><div class="composer-actions"><span>Ctrl + Enter 提交</span><a-button v-if="busy" status="danger" :disabled="!jobId" @click="cancel"><template #icon><IconStop /></template>取消任务</a-button><a-button v-else type="primary" :disabled="!feedback.trim() || historyLoading" @click="submit"><template #icon><IconSend /></template>{{ target ? '提交反馈' : '开始生成' }}</a-button></div></div>
    </div>
  </a-drawer>
</template>

<style>
.ai-drawer .arco-drawer-body { display: flex; padding-top: 18px; }
.ai-drawer-inner { display: flex; flex-direction: column; width: 100%; min-height: 0; height: 100%; }
.ai-context { display: flex; justify-content: space-between; gap: 16px; }
.ai-policy { font-size: 12px; color: var(--muted); margin: 8px 0 16px; line-height: 1.8; }
.ai-source-details summary { font-size: 12px; color: var(--muted); cursor: pointer; margin-bottom: 12px; }
.conversation-toolbar { display: flex; justify-content: space-between; border-block: 1px solid var(--border); padding: 7px 0; }
.conversation-history { padding: 12px; border-bottom: 1px solid var(--border); background: #f8f9fc; max-height: 160px; overflow: auto; }
.conversation-history button { display: flex; justify-content: space-between; gap: 20px; padding: 8px 10px; border: 0; background: none; cursor: pointer; width: 100%; border-radius: 4px; font-size: 12px; text-align: left; color: var(--muted); }
.conversation-history button.active { background: #e8e7ff; color: var(--primary); }
.job-progress { padding: 10px 0; display: flex; flex-direction: column; gap: 8px; }
.feedback-composer { padding-top: 14px; border-top: 1px solid var(--border); }
.feedback-composer label { font-size: 12px; color: #475569; display: block; margin-bottom: 8px; }
.composer-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; gap: 16px; }
.composer-actions > span { font-size: 11px; color: #98a1b1; }
</style>
