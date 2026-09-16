<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { pipelineApi, pipelineLabels, stageNames, type PipelineStage } from '../../api/pipelines'
import { runLabels } from '../../api/runs'
import type { Asset } from '../../api/types'
import { displayValue, formatTime, jobLabels } from '../../core/format'
import { usePipelineMonitor } from '../../composables/usePipelineMonitor'
import { RequestScope } from '../../core/request-scope'
import { useWorkspaceStore } from '../../stores/workspace'
import ErrorNotice from '../common/ErrorNotice.vue'
import GlobalFeedbackDrawer from '../ai/GlobalFeedbackDrawer.vue'
import RunHistoryDrawer from '../execution/RunHistoryDrawer.vue'
import PipelineAssets from './PipelineAssets.vue'
import PipelineResumeDialog from './PipelineResumeDialog.vue'

const props = defineProps<{ projectId: string; pipelineId: string }>()
const emit = defineEmits<{ changed: [] }>()
const workspace = useWorkspaceStore(), scope = new RequestScope()
function changed() { workspace.recordActivity(props.projectId); emit('changed') }
const monitor = usePipelineMonitor(changed), pipeline = monitor.pipeline
const feedbackVisible = ref(false), resumeVisible = ref(false), resumeStage = ref<PipelineStage>(), runVisible = ref(false), selectedRunId = ref('')
const busy = ref(false), actionError = ref<unknown>()
const steps = Object.keys(stageNames) as PipelineStage[]
const records = computed(() => new Map(pipeline.value?.steps.map(step => [step.stage, step]) ?? []))
const runs = computed(() => [...new Set([...(pipeline.value?.execution.runIds ?? []), pipeline.value?.runId].filter((id): id is string => !!id))])
const summary = computed(() => pipeline.value?.execution.runSummary)
const assetList = ref<InstanceType<typeof PipelineAssets>>()
watch([() => props.projectId, () => props.pipelineId], () => {
  scope.invalidate(); feedbackVisible.value = false; resumeVisible.value = false; runVisible.value = false; busy.value = false; actionError.value = undefined
  void monitor.start(props.projectId, props.pipelineId)
}, { immediate: true })
function resume(stage: PipelineStage) { resumeStage.value = stage; resumeVisible.value = true }
function showRun(id: string) { selectedRunId.value = id; runVisible.value = true }
async function cancel() {
  if (busy.value) return
  const token = scope.begin(`${props.projectId}:${props.pipelineId}`); busy.value = true; actionError.value = undefined
  try { await pipelineApi.cancel(props.projectId, props.pipelineId); if (scope.isCurrent(token)) { await monitor.refresh(); changed() } }
  catch (failure) { if (scope.isCurrent(token)) actionError.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
function updated() { void monitor.refresh(); changed() }
function feedbackApplied(assets: Asset[], deletedIds: string[]) { assetList.value?.applyChanges(assets, deletedIds); updated() }
onUnmounted(() => scope.invalidate())
</script>

<template>
  <div class="pipeline-detail">
    <div class="pipeline-detail-actions"><a-button :loading="monitor.loading.value" @click="monitor.refresh">刷新流水线</a-button><a-button v-if="pipeline" :disabled="monitor.active.value" @click="feedbackVisible = true">全链路反馈优化</a-button><a-button v-if="monitor.active.value" status="danger" :loading="busy" @click="cancel">取消流水线</a-button></div>
    <ErrorNotice :error="actionError ?? monitor.error.value" retry @retry="monitor.refresh" /><a-spin v-if="monitor.loading.value && !pipeline" tip="读取流水线检查点…" />
    <template v-if="pipeline">
      <div class="pipeline-detail-heading"><div><h2>全自动测试</h2><p class="small muted">{{ formatTime(pipeline.createdAt) }} · <code>{{ pipeline.id }}</code></p></div><a-tag data-testid="pipeline-status" :color="pipeline.status === 'COMPLETED' ? 'green' : pipeline.status === 'FAILED' ? 'red' : 'arcoblue'">{{ pipelineLabels[pipeline.status] ?? pipeline.status }}</a-tag></div>
      <a-progress :percent="pipeline.progress / 100" :status="pipeline.status === 'FAILED' ? 'danger' : 'normal'" />
      <p v-if="pipeline.config.sourceSnapshotId" data-testid="pipeline-source-snapshot" class="small muted source-binding">固定源码快照 <code>{{ pipeline.config.sourceSnapshotId }}</code> · 本流水线及其多轮反馈继续使用这份来源。</p>
      <a-alert v-if="pipeline.error" type="warning" class="pipeline-notice">{{ pipeline.error }}</a-alert>
      <p v-if="monitor.active.value" class="small muted">正在处理 {{ pipeline.currentStage }} · {{ stageNames[pipeline.currentStage] }}。关闭页面后可从工作台继续查看。</p>
      <p v-else class="small muted">{{ pipeline.status === 'COMPLETED_WITH_GAPS' ? '已保存可完成的部分。为待补充阶段配置依据后，可单独恢复。' : '各阶段结果已保存；生成结果可继续手工编辑或提交多轮反馈。' }} 流水线完成不代表全部测试通过。</p>
      <div class="pipeline-stages"><article v-for="stage in steps" :key="stage" :data-stage="stage" class="pipeline-stage"><header><h3><span>{{ stage }}</span>{{ stageNames[stage] }}</h3><a-tag data-testid="stage-status" :color="records.get(stage)?.status === 'COMPLETED' ? 'green' : records.get(stage)?.status === 'FAILED' ? 'red' : 'gray'">{{ pipelineLabels[records.get(stage)?.status ?? ''] ?? '尚未开始' }}</a-tag></header>
        <template v-if="records.get(stage)"><p v-if="records.get(stage)?.error || records.get(stage)?.output.reason" class="stage-reason">{{ records.get(stage)?.error || records.get(stage)?.output.reason }}</p><p class="small muted">第 {{ records.get(stage)?.attempt }} 次尝试 · {{ formatTime(records.get(stage)?.completedAt ?? records.get(stage)?.startedAt ?? '') }}</p><details><summary>阶段依据与结果</summary><pre class="json-view">{{ displayValue(records.get(stage)?.output) }}</pre></details><a-button v-if="!monitor.active.value && !['COMPLETED', 'SKIPPED'].includes(records.get(stage)!.status)" size="small" class="resume-stage" :aria-label="`补充配置并恢复 ${stage}`" @click="resume(stage)">补充配置并恢复</a-button></template>
      </article></div>
      <div v-if="monitor.job.job.value && monitor.active.value" class="pipeline-live"><p class="small muted">当前任务：{{ jobLabels[monitor.job.job.value.status] }} · {{ monitor.job.job.value.message }}</p><p v-if="monitor.job.reconnecting.value" class="small muted">事件连接正在恢复，进度将从持久化记录继续更新。</p><details v-if="monitor.job.streamedText.value"><summary>本阶段生成过程</summary><pre class="json-view">{{ monitor.job.streamedText.value }}</pre></details></div>
      <section v-if="pipeline.execution.execution" class="pipeline-execution"><h3>执行与诊断记录</h3><a-alert v-if="pipeline.execution.requiresExecutionResume" type="warning">{{ pipeline.execution.reason }}</a-alert><p v-if="pipeline.execution.execution === 'NOT_REQUESTED'" class="small muted">测试计划已生成。本次选择了仅生成资产，可在测试计划中发起执行。</p><div v-if="summary" class="stage-run-summary"><p class="small muted">阶段结束时的运行统计：{{ summary.total }} 个运行项 · {{ summary.caseCount }} 个用例 · {{ summary.dataRows }} 行数据</p><a-tag v-for="(count, status) in summary.counts" :key="status">{{ runLabels[status] ?? status }} {{ count }}</a-tag></div><div class="inline-actions"><a-button v-for="(id, index) in runs" :key="id" :aria-label="`查看流水线运行 ${index + 1}`" @click="showRun(id)">查看运行 {{ index + 1 }} · {{ id.slice(0, 8) }}</a-button></div></section>
      <PipelineAssets v-if="pipeline.assetIds.length" ref="assetList" :project-id="projectId" :asset-ids="pipeline.assetIds" :revision="pipeline.revision" @changed="updated" />
      <details class="pipeline-attempts"><summary>完整阶段历史 · {{ pipeline.attempts.length }} 次尝试</summary><article v-for="attempt in pipeline.attempts" :key="attempt.id"><h4>{{ attempt.stage }} · 第 {{ attempt.attempt }} 次 · {{ pipelineLabels[attempt.status] ?? attempt.status }}</h4><p class="small muted">{{ formatTime(attempt.startedAt ?? '') }} — {{ formatTime(attempt.completedAt ?? '') }}</p><p v-if="attempt.error" class="stage-reason">{{ attempt.error }}</p><details><summary>输入与输出快照</summary><pre class="json-view">{{ displayValue({ input: attempt.input, output: attempt.output }) }}</pre></details></article></details>
      <PipelineResumeDialog v-model:visible="resumeVisible" :pipeline="pipeline" :stage="resumeStage" @accepted="updated" />
      <GlobalFeedbackDrawer v-model:visible="feedbackVisible" :project-id="projectId" :pipeline-id="pipelineId" :source-snapshot-id="pipeline.config.sourceSnapshotId" :initial-conversation-id="pipeline.conversationId" @applied="feedbackApplied" />
      <RunHistoryDrawer v-model:visible="runVisible" :project-id="projectId" :initial-run-id="selectedRunId" />
    </template>
  </div>
</template>

<style scoped>
.pipeline-detail-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 10px; }.pipeline-detail-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-block: 24px; }.pipeline-detail-heading code { overflow-wrap: anywhere; }h2 { margin: 0; font-size: 22px; }.pipeline-notice { margin-top: 18px; }
.source-binding { overflow-wrap: anywhere; line-height: 1.7; padding: 12px; border: 1px solid var(--info-border); border-radius: 6px; background: var(--info-soft); }
.pipeline-stages { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-block: 24px; }.pipeline-stage { border: 1px solid var(--border); border-radius: 8px; padding: 18px; min-width: 0; }.pipeline-stage header { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }h3 { font-size: 15px; margin: 0; }.pipeline-stage h3 span { margin-right: 10px; color: var(--primary); font-size: 12px; }summary { cursor: pointer; font-size: 12px; color: var(--muted); }.json-view { max-height: 360px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }.stage-reason { line-height: 1.7; color: var(--warning); font-size: 13px; overflow-wrap: anywhere; }.resume-stage { margin-top: 16px; }
.pipeline-execution, .pipeline-live { border: 1px solid var(--border); border-radius: 8px; padding: 18px; margin-block: 20px; }.pipeline-execution h3 { margin-bottom: 14px; }.stage-run-summary { margin-block: 16px; }.stage-run-summary .arco-tag { margin: 0 8px 10px 0; }.inline-actions { flex-wrap: wrap; }.pipeline-attempts { margin-top: 30px; }.pipeline-attempts article { border-bottom: 1px solid var(--border); padding-block: 16px; }
@media(max-width: 720px) { .pipeline-stages { grid-template-columns: 1fr; }.pipeline-detail-heading { align-items: flex-start; flex-direction: column; } }
</style>
