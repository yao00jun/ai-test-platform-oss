<script setup lang="ts">
import { computed } from 'vue'
import type { DiagnosisOccurrence } from '../../api/runs'
import { isRecord } from '../../api/client'
import { formatTime } from '../../core/format'
import type { FailureSourceEvidence } from '../../api/bugs'
import FailureSourceEvidenceView from '../bugs/FailureSourceEvidence.vue'

const props = withDefaults(defineProps<{ occurrence: DiagnosisOccurrence; showRunLink?: boolean }>(), { showRunLink: false })
const emit = defineEmits<{ openRun: [id: string] }>()
const statuses: Record<string, string> = { CREATED: '首次创建', RECURRENCE: '再次发生', SUPPRESSED_DELETED: '已删除缺陷的再次发生' }
const evidence = computed(() => isRecord(props.occurrence.evidence) ? props.occurrence.evidence : {})
const diagnosis = computed(() => isRecord(props.occurrence.diagnosis) ? props.occurrence.diagnosis : {})
const fields = computed(() => [
  ['severity', '严重度'], ['rootCauseAnalysis', '根因推测与依据'], ['fixSuggestion', '修复建议'],
].flatMap(([key, label]) => typeof diagnosis.value[key!] === 'string' ? [{ key, label, value: String(diagnosis.value[key!]) }] : []))
const row = computed(() => typeof evidence.value.rowIndex === 'number' && Number.isInteger(evidence.value.rowIndex) ? evidence.value.rowIndex + 1 : undefined)
const source = computed(() => {
  const value = evidence.value.sourceEvidence
  return isRecord(value) && value.formatVersion === 'aitest.failure-source-evidence/v1' && ['bindings', 'locations', 'diffs', 'diagnostics'].every(key => Array.isArray(value[key])) ? value as unknown as FailureSourceEvidence : undefined
})
const code = computed(() => isRecord(diagnosis.value.codeDiagnosis) ? diagnosis.value.codeDiagnosis : undefined)
</script>

<template>
  <article class="diagnosis-card" :aria-label="`诊断记录 ${occurrence.id}`">
    <header><a-tag :color="occurrence.status === 'CREATED' ? 'arcoblue' : 'orange'">{{ statuses[occurrence.status] ?? occurrence.status }}</a-tag><time>{{ formatTime(occurrence.createdAt) }}</time></header>
    <h4 v-if="typeof diagnosis.title === 'string'">{{ diagnosis.title }}</h4>
    <p v-if="diagnosis.source === 'EXISTING_DEFECT'" class="small muted">此次记录保留新的失败证据，已有缺陷的人工结论与状态保持。</p>
    <p class="diagnosis-origin small"><span v-if="evidence.caseName">{{ evidence.caseName }}</span><span v-if="row !== undefined">数据行 {{ row }}</span><span v-if="evidence.stepName">步骤：{{ evidence.stepName }}</span><span v-if="evidence.engine">{{ evidence.engine }}</span></p>
    <dl v-if="fields.length"><template v-for="field in fields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
    <details v-if="source" class="diagnosis-original"><summary>此次发生的源码定位</summary><FailureSourceEvidenceView :evidence="source" /><template v-if="code"><p v-if="code.root_cause" class="small">{{ code.root_cause }}</p><pre v-if="code.suggested_fix" class="json-view">{{ code.suggested_fix }}</pre></template></details>
    <div class="diagnosis-run"><a-button v-if="showRunLink" type="text" size="small" :aria-label="`查看来源运行 ${occurrence.runId}`" @click="emit('openRun', occurrence.runId)">查看来源运行</a-button><code class="small muted">{{ occurrence.runId }}</code></div>
    <details class="diagnosis-original"><summary>诊断时的原始证据</summary><p class="small muted">用例、数据行、步骤与观察均来自这次诊断保存的记录。</p><pre data-testid="diagnosis-evidence" class="json-view">{{ JSON.stringify(occurrence.evidence, null, 2) }}</pre></details>
    <details class="diagnosis-full"><summary>完整诊断与来源</summary><p class="small muted">模型：{{ occurrence.modelStamp }} · 提示词：{{ occurrence.promptVersion }}</p><p class="small muted">缺陷 {{ occurrence.bugId }}</p><pre class="json-view">{{ JSON.stringify(occurrence.diagnosis, null, 2) }}</pre></details>
  </article>
</template>

<style scoped>
.diagnosis-card { padding: 18px; border: 1px solid var(--border); border-radius: 10px; margin-top: 16px; min-width: 0; overflow-wrap: anywhere; }.diagnosis-card header, .diagnosis-origin, .diagnosis-run { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.diagnosis-card time { font-size: 12px; color: var(--muted); }.diagnosis-card h4 { margin: 16px 0 10px; font-size: 15px; }.diagnosis-card dl { font-size: 13px; margin: 16px 0; }.diagnosis-card dt { font-weight: 600; margin-top: 12px; }.diagnosis-card dd { margin: 5px 0 0; white-space: pre-wrap; }.diagnosis-card summary { cursor: pointer; font-size: 12px; }.diagnosis-original, .diagnosis-full { margin-top: 16px; }.diagnosis-card .json-view { max-height: 440px; overflow: auto; }
@media (max-width: 600px) { .diagnosis-card { padding: 12px; } }
</style>
