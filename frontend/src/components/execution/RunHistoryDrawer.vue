<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { runApi, runLabels, type RunRecord } from '../../api/runs'
import { RequestScope } from '../../core/request-scope'
import { formatTime } from '../../core/format'
import ErrorNotice from '../common/ErrorNotice.vue'
import EmptyState from '../common/EmptyState.vue'
import RunDetail from './RunDetail.vue'

const props = defineProps<{ projectId: string; initialRunId?: string }>()
const visible = defineModel<boolean>('visible', { default: false })
const items = ref<RunRecord[]>([]), selectedId = ref(''), total = ref(0), page = ref(1), loading = ref(false), error = ref<unknown>()
const scope = new RequestScope()
watch(() => [visible.value, props.projectId, props.initialRunId], () => {
  scope.invalidate(); items.value = []; total.value = 0; page.value = 1; error.value = undefined; loading.value = false
  selectedId.value = props.initialRunId ?? ''
  if (visible.value && props.projectId && !selectedId.value) void load()
}, { immediate: true })
async function load() {
  if (!visible.value || !props.projectId) return
  const token = scope.begin(props.projectId); loading.value = true; error.value = undefined
  try {
    const result = await runApi.list(props.projectId, (page.value - 1) * 50, token.signal)
    if (scope.isCurrent(token)) { items.value = result.items; total.value = result.total }
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
function back() { selectedId.value = ''; void load() }
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-drawer v-model:visible="visible" title="执行记录" role="dialog" aria-label="执行记录" :width="1060" :footer="false" unmount-on-close>
    <RunDetail v-if="visible && selectedId" :key="`${projectId}:${selectedId}`" :project-id="projectId" :run-id="selectedId" @back="back" />
    <template v-else>
      <div class="run-history-toolbar"><p class="small muted">每次运行独立保存配置、数据行、步骤证据和人工判定。</p><a-button :loading="loading" @click="load">刷新执行列表</a-button></div>
      <ErrorNotice :error="error" retry @retry="load" />
      <a-spin v-if="loading && !items.length" tip="读取执行记录…" />
      <div v-else-if="items.length" class="table-overflow"><table class="asset-table" aria-label="运行列表"><thead><tr><th>测试资产</th><th>结果</th><th>运行项 / 用例 / 数据行</th><th>开始时间</th></tr></thead><tbody><tr v-for="run in items" :key="run.id"><td><button type="button" class="run-name" :aria-label="`查看运行 ${run.name}`" @click="selectedId = run.id">{{ run.name }}</button><p class="small muted mono">{{ run.id.slice(0, 12) }}</p></td><td>{{ runLabels[run.status] ?? run.status }}</td><td>{{ run.summary.total }} / {{ run.summary.caseCount }} / {{ run.summary.dataRows }}</td><td>{{ formatTime(run.createdAt) }}</td></tr></tbody></table></div>
      <EmptyState v-else-if="!error" title="还没有执行记录" description="从测试用例、接口、场景、UI 或测试计划发起一次运行。" />
      <a-pagination v-if="total > 50" v-model:current="page" :total="total" :page-size="50" style="margin-block: 20px" @change="load" />
    </template>
  </a-drawer>
</template>

<style scoped>
.run-history-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-block: 10px 20px; }.run-name { border: 0; background: none; color: var(--primary); cursor: pointer; font: inherit; text-align: left; padding: 0; }.asset-table { min-width: 660px; }
</style>
