<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { allAssets } from '../../api/assets'
import { ApiError } from '../../api/client'
import { pipelineApi, stageNames, type Pipeline, type PipelineAccepted, type PipelineOptions, type PipelineResume, type PipelineStage } from '../../api/pipelines'
import type { Asset } from '../../api/types'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import PipelineConfigFields from './PipelineConfigFields.vue'
import SourceSnapshotSelect from '../analysis/SourceSnapshotSelect.vue'

const props = defineProps<{ pipeline: Pipeline; stage?: PipelineStage }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ accepted: [result: PipelineAccepted] }>()
const environments = ref<Asset[]>([]), databases = ref<Asset[]>([]), recordings = ref<Asset[]>([]), definitions = ref<Asset[]>([])
const apiIds = ref<string[]>([]), options = ref<PipelineOptions>({ environmentId: '', databaseSourceIds: [], uiEvidenceIds: [], execute: false })
const loading = ref(false), busy = ref(false), error = ref<unknown>()
const scope = new RequestScope(); let current: ScopeToken | undefined, pending: PipelineResume | undefined
watch([() => visible.value, () => props.pipeline.projectId, () => props.pipeline.id, () => props.stage], async () => {
  scope.invalidate(); current = undefined; pending = undefined; loading.value = false; busy.value = false; error.value = undefined
  if (!visible.value || !props.stage) return
  const pipeline = props.pipeline, config = pipeline.config
  options.value = { environmentId: config.environmentId ?? '', databaseSourceIds: [...(config.databaseSourceIds ?? [])], uiEvidenceIds: [...(config.uiEvidenceIds ?? [])], execute: config.execute, sourceSnapshotId: config.sourceSnapshotId ?? '' }
  apiIds.value = [...config.apiDefinitionIds]
  const token = scope.begin(`${pipeline.projectId}:${pipeline.id}:${props.stage}`); current = token; loading.value = true
  const results = await Promise.allSettled(['ENVIRONMENT', 'DATABASE_SOURCE', 'UI_SCENARIO', 'UI_STEP', 'API_DEFINITION'].map(type => allAssets(pipeline.projectId, type as Asset['type'], token.signal)))
  if (!scope.isCurrent(token)) return
  const read = (index: number) => { const result = results[index]!; if (result.status === 'fulfilled') return result.value; error.value = result.reason; return [] }
  environments.value = read(0); databases.value = read(1); recordings.value = [...read(2), ...read(3)]; definitions.value = read(4); loading.value = false
}, { immediate: true })
async function submit() {
  const token = current, stage = props.stage, pipeline = props.pipeline
  if (!token || !stage || busy.value || loading.value) return
  const body = { projectId: pipeline.projectId, stage, apiDefinitionIds: [...apiIds.value], environmentId: options.value.environmentId, databaseSourceIds: [...options.value.databaseSourceIds], uiEvidenceIds: [...options.value.uiEvidenceIds], execute: options.value.execute, sourceSnapshotId: options.value.sourceSnapshotId }
  if (!pending || JSON.stringify({ ...pending, idempotencyKey: undefined }) !== JSON.stringify(body)) pending = { ...body, idempotencyKey: crypto.randomUUID() }
  busy.value = true; error.value = undefined
  try {
    const result = await pipelineApi.resume(pipeline.id, pending)
    if (!scope.isCurrent(token)) return
    pending = undefined; visible.value = false; emit('accepted', result)
  } catch (failure) {
    if (scope.isCurrent(token)) { error.value = failure; if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) pending = undefined }
  } finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-modal v-model:visible="visible" :width="700" title="补充配置并恢复阶段" role="dialog" aria-label="补充配置并恢复阶段" :footer="false" unmount-on-close>
    <h3>{{ stage }} · {{ stage ? stageNames[stage] : '' }}</h3>
    <p class="small muted resume-explanation">已完成阶段及已有资产保留。此操作继续所选阶段和后续未完成部分；已完成结果可通过全链路反馈修改。</p>
    <a-alert v-if="stage === 'S6' && pipeline.execution.requiresExecutionResume" type="warning" class="resume-explanation">{{ pipeline.execution.reason }} 勾选执行后会按当前计划运行待执行项。</a-alert>
    <ErrorNotice :error="error" /><a-spin v-if="loading" tip="读取可用配置…" />
    <SourceSnapshotSelect v-model="options.sourceSnapshotId" :project-id="pipeline.projectId" :locked="!!pipeline.config.sourceSnapshotId" :disabled="busy || loading" style="margin-block: 18px" />
    <template v-if="!loading"><label class="resume-apis">开发接口定义<select v-model="apiIds" aria-label="恢复阶段的接口定义" multiple :disabled="busy" size="4"><option v-for="asset in definitions" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label><PipelineConfigFields v-model="options" :environments="environments" :databases="databases" :recordings="recordings" :environment-locked="!!pipeline.runId" :disabled="busy" /></template>
    <div class="resume-actions"><a-button type="primary" :loading="busy" :disabled="loading" @click="submit">恢复所选阶段</a-button></div>
  </a-modal>
</template>

<style scoped>
h3 { margin-top: 0; }.resume-explanation { line-height: 1.7; margin-block: 16px; }.resume-apis { display: grid; gap: 8px; margin-block: 20px; font-size: 13px; }.resume-apis select { font: inherit; border: 1px solid var(--border); border-radius: 6px; padding: 10px; }.resume-actions { display: flex; justify-content: flex-end; margin-top: 24px; }
</style>
