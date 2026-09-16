<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { runApi, runLabels, type RunStep } from '../../api/runs'
import { saveDownload } from '../../api/client'
import { displayValue } from '../../core/format'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
const props = defineProps<{ projectId: string; step: RunStep }>()
const busy = ref(''), error = ref<unknown>(), imageUrl = ref('')
const scope = new RequestScope()
async function artifact(id: string, preview = false) {
  if (busy.value) return
  const token = scope.begin(`${props.projectId}:${id}`); busy.value = id; error.value = undefined
  try {
    const file = await runApi.file(props.projectId, id, token.signal)
    if (!scope.isCurrent(token)) return
    if (preview && file.blob.type.startsWith('image/')) { if (imageUrl.value) URL.revokeObjectURL(imageUrl.value); imageUrl.value = URL.createObjectURL(file.blob) }
    else saveDownload(file)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = '' }
}
onUnmounted(() => { scope.invalidate(); if (imageUrl.value) URL.revokeObjectURL(imageUrl.value) })
</script>

<template>
  <details class="step-evidence"><summary><span>步骤证据 · {{ step.name }}</span> <span class="muted small">{{ runLabels[step.status] ?? step.status }} · {{ step.result.durationMs }} ms</span></summary>
    <p v-if="step.result.error" class="step-error">{{ step.result.error }}</p>
    <div class="evidence-panels"><section><h5>请求与动作</h5><pre class="json-view">{{ displayValue(step.result.request) }}</pre></section><section><h5>实际结果</h5><pre class="json-view">{{ displayValue(step.result.actual) }}</pre></section></div>
    <div v-if="step.result.assertions?.length" class="table-overflow"><table class="asset-table" aria-label="步骤断言"><thead><tr><th>断言</th><th>预期</th><th>实际</th><th>判定</th></tr></thead><tbody><tr v-for="(assertion, index) in step.result.assertions" :key="index"><td>{{ assertion.type }} {{ assertion.path }}<p class="small muted">{{ assertion.message }}</p></td><td><pre>{{ displayValue(assertion.expected) }}</pre></td><td><pre>{{ displayValue(assertion.actual) }}</pre></td><td>{{ assertion.passed ? '通过' : '失败' }}</td></tr></tbody></table></div>
    <details><summary class="small muted">提取的变量</summary><pre class="json-view">{{ displayValue(step.result.exports) }}</pre></details>
    <ErrorNotice :error="error" />
    <div v-if="step.result.artifactIds?.length" class="artifact-list"><span class="small muted">受管附件</span><div v-for="(id, index) in step.result.artifactIds" :key="id" class="inline-actions"><a-button size="small" :disabled="!!busy" @click="artifact(id)">下载附件 {{ index + 1 }}</a-button><a-button size="small" :disabled="!!busy" @click="artifact(id, true)">预览图片或下载文件 {{ index + 1 }}</a-button><code>{{ id.slice(0, 10) }}</code></div></div>
    <img v-if="imageUrl" :src="imageUrl" :alt="`${step.name} 的执行截图`" class="evidence-image">
  </details>
</template>

<style scoped>
.step-evidence { border: 1px solid var(--border); border-radius: 7px; padding: 14px; margin-block: 10px; }
summary { cursor: pointer; line-height: 1.8; }h5 { margin: 12px 0 6px; font-size: 12px; }.evidence-panels { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.evidence-panels section { min-width: 0; }.json-view { max-height: 340px; overflow: auto; }pre { white-space: pre-wrap; overflow-wrap: anywhere; }.step-error { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--danger); font-size: 12px; }
.artifact-list { display: grid; gap: 8px; margin-top: 12px; }.artifact-list code { font-size: 11px; color: var(--muted); }.evidence-image { max-width: 100%; margin-top: 16px; border: 1px solid var(--border); border-radius: 6px; }
@media(max-width: 700px) { .evidence-panels { grid-template-columns: 1fr; } }
</style>
