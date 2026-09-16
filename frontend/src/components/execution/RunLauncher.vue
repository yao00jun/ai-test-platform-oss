<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { allAssets } from '../../api/assets'
import { runApi, type RunInput, type RunSubmission, type ValidationResult } from '../../api/runs'
import type { Asset } from '../../api/types'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ target?: Asset }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ submitted: [result: RunSubmission, projectId: string] }>()
const environments = ref<Asset[]>([]), datasets = ref<Asset[]>([])
const environmentId = ref(''), datasetId = ref('')
const loading = ref(false), busy = ref(false), error = ref<unknown>(), validation = ref<ValidationResult>()
const scope = new RequestScope()
let current: ScopeToken | undefined, pending: RunInput | undefined
watch(() => [visible.value, props.target?.projectId, props.target?.id], async () => {
  scope.invalidate(); current = undefined; pending = undefined
  environments.value = []; datasets.value = []; validation.value = undefined; error.value = undefined; busy.value = false; loading.value = false
  environmentId.value = ''; datasetId.value = ''
  if (!visible.value || !props.target) return
  const target = props.target, token = scope.begin(`${target.projectId}:${target.id}`); current = token; loading.value = true
  const results = await Promise.allSettled([allAssets(target.projectId, 'ENVIRONMENT', token.signal), allAssets(target.projectId, 'DATASET', token.signal)])
  if (!scope.isCurrent(token)) return
  if (results[0].status === 'fulfilled') environments.value = results[0].value
  else error.value = results[0].reason
  if (results[1].status === 'fulfilled') datasets.value = results[1].value
  else error.value = results[1].reason
  environmentId.value = typeof target.data.environmentId === 'string' && target.data.environmentId ? target.data.environmentId : environments.value.length === 1 ? environments.value[0]!.id : ''
  loading.value = false
}, { immediate: true })
watch([environmentId, datasetId], () => { validation.value = undefined })
async function check(execute: boolean) {
  const token = current, target = props.target
  if (!token || !target || busy.value || loading.value) return
  const options = { environmentId: environmentId.value || undefined, datasetId: datasetId.value || undefined }
  const identity = { assetId: target.id, ...options }
  if (!pending || JSON.stringify({ ...pending, idempotencyKey: undefined }) !== JSON.stringify(identity)) pending = { ...identity, idempotencyKey: crypto.randomUUID() }
  const request = pending
  busy.value = true; error.value = undefined
  try {
    const checked = await runApi.validate(target.projectId, target.id, options, token.signal)
    if (!scope.isCurrent(token)) return
    validation.value = checked
    if (!execute || !checked.valid) return
    const result = await runApi.submit(target.projectId, request)
    if (!scope.isCurrent(token)) return
    pending = undefined; visible.value = false; emit('submitted', result, target.projectId)
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-modal v-model:visible="visible" title="执行测试" role="dialog" aria-label="执行测试" :width="560" :footer="false" unmount-on-close>
    <h3>{{ target?.name }}</h3>
    <p class="small muted">运行会保存提交时的资产与配置。功能用例进入人工清单，自动化项执行后记录实际证据。</p>
    <ErrorNotice :error="error" />
    <a-spin v-if="loading" tip="读取执行配置…" />
    <div v-else class="run-launch-fields">
      <label>执行环境<select v-model="environmentId" aria-label="执行环境" :disabled="busy"><option value="">使用资产配置</option><option v-for="environment in environments" :key="environment.id" :value="environment.id">{{ environment.name }}</option></select></label>
      <label>数据集<select v-model="datasetId" aria-label="执行数据集" :disabled="busy"><option value="">使用各资产或计划项绑定的数据集</option><option v-for="dataset in datasets" :key="dataset.id" :value="dataset.id">{{ dataset.name }}</option></select></label>
    </div>
    <div v-if="validation" class="run-validation"><a-alert :type="validation.valid ? 'success' : 'warning'">{{ validation.valid ? `已检查 ${validation.checkedItems} 个执行项，变量与引用可解析。` : '请先修正以下执行配置。' }}</a-alert><p v-for="(issue, index) in validation.errors" :key="index" class="small">{{ issue.name }} · {{ issue.field }}{{ issue.rowIndex === undefined ? '' : ` · 数据行 ${issue.rowIndex}` }}：{{ issue.message }}</p><p v-if="validation.errorsTruncated" class="small muted">问题较多，仅显示第一批，请修复后继续检查。</p></div>
    <div class="run-launch-actions"><a-button :disabled="loading || busy" @click="check(false)">检查变量与引用</a-button><a-button type="primary" :disabled="loading" :loading="busy" @click="check(true)">开始执行</a-button></div>
  </a-modal>
</template>

<style scoped>
.run-launch-fields { display: grid; gap: 18px; margin-block: 24px; }
.run-launch-fields label { display: grid; gap: 8px; font-size: 13px; }
select { width: 100%; border: 1px solid var(--border); border-radius: 6px; padding: 9px 12px; color: var(--text); background: var(--surface); }
.run-launch-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
.run-validation { margin-top: 16px; }
</style>
