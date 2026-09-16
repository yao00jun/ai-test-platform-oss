<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { pipelineApi, pipelineLabels, stageNames, type PipelineAccepted, type PipelineSummary } from '../../api/pipelines'
import { formatTime } from '../../core/format'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import PipelineWizard from './PipelineWizard.vue'
import PipelineDetail from './PipelineDetail.vue'

const props = defineProps<{ projectId: string }>()
const records = ref<PipelineSummary[]>([]), loading = ref(false), error = ref<unknown>(), wizardVisible = ref(false), detailVisible = ref(false), selectedId = ref('')
const scope = new RequestScope()
watch(() => props.projectId, () => { scope.invalidate(); records.value = []; loading.value = false; error.value = undefined; wizardVisible.value = false; detailVisible.value = false; selectedId.value = ''; void load() }, { immediate: true })
async function load() {
  const token = scope.begin(props.projectId); loading.value = true; error.value = undefined
  try { const result = await pipelineApi.list(props.projectId, token.signal); if (scope.isCurrent(token)) records.value = result }
  catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
function open(id: string) { selectedId.value = id; detailVisible.value = true }
function accepted(result: PipelineAccepted, projectId: string) { if (props.projectId === projectId) { open(result.pipelineId); void load() } }
onUnmounted(() => scope.invalidate())
</script>

<template>
  <section class="surface pipeline-workspace" aria-label="全自动测试工作流">
    <div class="pipeline-workspace-header"><div><h2>从需求到测试资产</h2><p class="small muted">提交 BA 与接口文档，贯通分析、用例、接口链、SQL、UI 和执行诊断。</p></div><a-button type="primary" aria-label="一键全自动生成全套测试资产" @click="wizardVisible = true">🚀 一键全自动生成全套测试资产</a-button></div>
    <div class="pipeline-workspace-toolbar"><span class="small muted">最近流水线 · 最多显示 100 条</span><a-button type="text" size="small" :loading="loading" @click="load">刷新流水线列表</a-button></div>
    <ErrorNotice :error="error" retry @retry="load" />
    <div v-if="records.length" class="pipeline-recent-list"><article v-for="record in records" :key="record.id" class="pipeline-recent"><div><a-button type="text" :aria-label="`查看流水线 ${record.id.slice(0, 8)}`" @click="open(record.id)">{{ record.id.slice(0, 8) }} · {{ stageNames[record.currentStage] }}</a-button><span class="small muted">{{ formatTime(record.createdAt) }}</span></div><span class="small">{{ pipelineLabels[record.status] ?? record.status }}</span></article></div>
    <p v-else-if="!loading && !error" class="small muted pipeline-empty">尚未启动流水线。每次生成和后续反馈都会保留独立记录。</p>
    <PipelineWizard v-model:visible="wizardVisible" :project-id="projectId" @accepted="accepted" />
    <a-drawer v-model:visible="detailVisible" title="全自动测试流水线" role="dialog" aria-label="全自动测试流水线" :width="1150" :footer="false" unmount-on-close><PipelineDetail v-if="detailVisible" :key="`${projectId}:${selectedId}`" :project-id="projectId" :pipeline-id="selectedId" @changed="load" /></a-drawer>
  </section>
</template>

<style scoped>
.pipeline-workspace { margin-bottom: 28px; padding: 24px; }.pipeline-workspace-header { display: flex; justify-content: space-between; align-items: center; gap: 20px; }.pipeline-workspace h2 { font-size: 19px; margin: 0; }.pipeline-workspace p { line-height: 1.7; }.pipeline-workspace-toolbar { display: flex; justify-content: space-between; align-items: center; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border); }.pipeline-recent-list { max-height: 260px; overflow: auto; }.pipeline-recent { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding-block: 10px; border-bottom: 1px solid var(--border-subtle); }.pipeline-recent > div { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.pipeline-empty { margin-bottom: 0; }
@media(max-width: 760px) { .pipeline-workspace-header { align-items: stretch; flex-direction: column; }.pipeline-workspace-header .arco-btn { white-space: normal; height: auto; min-height: 40px; padding-block: 8px; } }
</style>
