<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { runApi, type DiagnosisOccurrence } from '../../api/runs'
import { RequestScope } from '../../core/request-scope'
import DiagnosisOccurrenceCard from '../execution/DiagnosisOccurrenceCard.vue'
import RunHistoryDrawer from '../execution/RunHistoryDrawer.vue'
import ErrorNotice from '../common/ErrorNotice.vue'
import EmptyState from '../common/EmptyState.vue'

const props = defineProps<{ projectId: string; bugId: string }>()
const items = ref<DiagnosisOccurrence[]>([]), count = ref(0), page = ref(1)
const loading = ref(false), error = ref<unknown>(), runId = ref(''), runVisible = ref(false)
const scope = new RequestScope()
const shown = computed(() => items.value.slice((page.value - 1) * 20, page.value * 20))
watch(() => [props.projectId, props.bugId], () => {
  scope.invalidate(); items.value = []; count.value = 0; page.value = 1; error.value = undefined; loading.value = false; runId.value = ''; runVisible.value = false
  void load()
}, { immediate: true })
async function load() {
  if (!props.projectId || !props.bugId || loading.value) return
  const project = props.projectId, id = props.bugId, token = scope.begin(`${project}:${id}`)
  loading.value = true; error.value = undefined
  try {
    const result = await runApi.occurrences(project, id, token.signal)
    if (!scope.isCurrent(token)) return
    items.value = result.items; count.value = result.occurrenceCount
    page.value = Math.min(page.value, Math.max(1, Math.ceil(items.value.length / 20)))
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
function openRun(id: string) { runId.value = id; runVisible.value = true }
onUnmounted(() => scope.invalidate())
</script>

<template>
  <section role="region" aria-label="缺陷发生记录" class="bug-history">
    <div class="bug-history-heading"><h3>累计发生 {{ count }} 次</h3><a-button :loading="loading" @click="load">刷新发生记录</a-button></div>
    <p class="small muted">每次失败保留独立的运行与诊断证据。重复发生不会更改缺陷的人工状态、结论或版本。</p>
    <ErrorNotice :error="error" retry @retry="load" />
    <a-spin v-if="loading && !items.length" tip="读取发生记录…" />
    <EmptyState v-else-if="!items.length && !error" title="还没有发生记录" description="手工创建或导入的缺陷可以独立维护；执行失败诊断后，相关发生记录会显示在这里。" />
    <DiagnosisOccurrenceCard v-for="occurrence in shown" :key="occurrence.id" :occurrence="occurrence" show-run-link @open-run="openRun" />
    <a-pagination v-if="items.length > 20" v-model:current="page" :total="items.length" :page-size="20" simple style="margin-top: 18px" />
    <RunHistoryDrawer v-model:visible="runVisible" :project-id="projectId" :initial-run-id="runId" />
  </section>
</template>

<style scoped>
.bug-history { min-width: 0; }.bug-history-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }.bug-history-heading h3 { font-size: 17px; margin: 0; }
</style>
