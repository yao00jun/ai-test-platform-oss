<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { analysisApi, sourceStatusLabels, type SourceAnalysis } from '../../api/analysis'
import { formatTime } from '../../core/format'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = withDefaults(defineProps<{ projectId: string; label?: string; emptyLabel?: string; disabled?: boolean; locked?: boolean }>(), { label: '生成源码快照', emptyLabel: '不绑定源码快照' })
const selected = defineModel<string>({ default: '' })
const rows = ref<SourceAnalysis[]>([]), total = ref(0), offset = ref(0), loading = ref(false), error = ref<unknown>()
const scope = new RequestScope()
const selectedRow = computed(() => rows.value.find(row => row.id === selected.value))
const available = (row: SourceAnalysis) => ['READY', 'PARTIAL'].includes(row.status)
watch(() => props.projectId, () => { scope.invalidate(); rows.value = []; total.value = 0; offset.value = 0; error.value = undefined; void read(true) }, { immediate: true })
async function read(reset = false) {
  if (!props.projectId) return
  const project = props.projectId, token = scope.begin(project)
  loading.value = true; error.value = undefined
  try {
    const nextOffset = reset ? 0 : offset.value
    const result = await analysisApi.list(project, nextOffset, token.signal)
    if (!scope.isCurrent(token)) return
    rows.value = [...new Map([...(reset ? [] : rows.value), ...result.items].map(row => [row.id, row])).values()]
    offset.value = nextOffset + result.items.length; total.value = result.total
    if (selected.value && !rows.value.some(row => row.id === selected.value)) {
      const detail = await analysisApi.get(project, selected.value, token.signal)
      if (scope.isCurrent(token)) rows.value = [detail, ...rows.value]
    }
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <div class="snapshot-choice">
    <label>{{ label }}<select v-model="selected" :aria-label="label" :disabled="disabled || locked || loading"><option value="">{{ emptyLabel }}</option><option v-if="selected && !selectedRow" :value="selected">{{ selected }} · 等待核对</option><option v-for="row in rows" :key="row.id" :value="row.id" :disabled="!available(row)">{{ formatTime(row.createdAt) }} · {{ row.id.slice(0, 8) }} · {{ sourceStatusLabels[row.status] ?? row.status }}</option></select></label>
    <p v-if="selectedRow" class="small muted">{{ selectedRow.fileCount }} 个固定文件 · {{ selectedRow.id }}<span v-if="selectedRow.status === 'PARTIAL'"> · 含解析缺口，请在项目源码分析中查看诊断。</span></p>
    <p v-if="locked" class="small muted">此流水线继续使用原始源码快照。</p>
    <div v-if="!locked" class="snapshot-actions"><a-button size="mini" :loading="loading" :disabled="disabled" @click="read(true)">刷新源码快照</a-button><a-button v-if="offset < total" size="mini" :disabled="loading || disabled" @click="read()">加载更多快照</a-button></div>
    <ErrorNotice :error="error" />
  </div>
</template>

<style scoped>
.snapshot-choice { min-width: 0; display: grid; gap: 9px; }.snapshot-choice label { display: grid; gap: 7px; font-size: 13px; }select { box-sizing: border-box; min-width: 0; width: 100%; border: 1px solid var(--border); border-radius: 6px; background: white; color: var(--text); padding: 9px 10px; font: inherit; }p { margin: 0; line-height: 1.7; overflow-wrap: anywhere; }.snapshot-actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
