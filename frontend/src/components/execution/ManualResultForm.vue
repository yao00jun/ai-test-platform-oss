<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ApiError } from '../../api/client'
import { runApi, runLabels, type RunDetail, type RunItem } from '../../api/runs'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ projectId: string; runId: string; item: RunItem }>()
const emit = defineEmits<{ saved: [run: RunDetail] }>()
const baseline = ref<RunItem>(), latest = ref<RunItem>()
const status = ref(''), notes = ref(''), busy = ref(false), notice = ref(''), error = ref<unknown>()
const scope = new RequestScope()
const dirty = computed(() => !!baseline.value && (status.value !== selectedStatus(baseline.value) || notes.value !== (baseline.value.notes ?? '')))
const conflict = computed(() => latest.value && baseline.value && latest.value.manualVersion !== baseline.value.manualVersion)
function selectedStatus(item: RunItem) { return ['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED'].includes(item.status) ? item.status : '' }
function reset(item: RunItem) { baseline.value = item; latest.value = item; status.value = selectedStatus(item); notes.value = item.notes ?? ''; error.value = undefined }
watch([() => props.projectId, () => props.runId, () => props.item.id], () => { scope.invalidate(); busy.value = false; notice.value = ''; reset(props.item) }, { immediate: true })
watch(() => props.item, item => {
  if (item.id !== baseline.value?.id) return
  latest.value = item
  if (!dirty.value && !busy.value) reset(item)
})
function keep() { if (latest.value) { baseline.value = latest.value; error.value = undefined; notice.value = '已采用最新版本，保留你的判定和备注；请再次保存。' } }
async function save() {
  const item = baseline.value
  if (!item || !status.value || busy.value) return
  const token = scope.begin(`${props.projectId}:${props.runId}:${item.id}`)
  const project = props.projectId, runId = props.runId
  busy.value = true; error.value = undefined; notice.value = ''
  try {
    const run = await runApi.manual(project, runId, { itemId: item.id, baseVersion: item.manualVersion, status: status.value, notes: notes.value })
    if (!scope.isCurrent(token)) return
    const updated = run.items.find(entry => entry.id === item.id)
    if (updated) reset(updated)
    notice.value = '人工结果已保存'; emit('saved', run)
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && failure.status === 409) {
      try {
        const run = await runApi.get(project, runId, token.signal)
        if (scope.isCurrent(token)) latest.value = run.items.find(entry => entry.id === item.id)
      } catch { /* Keep the conflict and the user's draft when current state cannot be read. */ }
    }
  } finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <form :aria-label="`人工结果 ${item.name}`" class="manual-result-form" @submit.prevent="save">
    <h4>人工执行记录</h4><ErrorNotice :error="error" />
    <label>人工判定<select v-model="status" aria-label="人工判定" :disabled="busy" required><option value="" disabled>选择判定</option><option v-for="value in ['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED']" :key="value" :value="value">{{ runLabels[value] }}</option></select></label>
    <label>人工备注<textarea v-model="notes" aria-label="人工备注" :disabled="busy" :maxlength="20000" rows="3" placeholder="填写实际检查过程、观察结果与阻塞原因" /></label>
    <div v-if="conflict && latest" class="manual-conflict"><strong>服务器上已有更新的人工结果</strong><p>判定：{{ runLabels[latest.status] }}</p><p class="manual-notes">{{ latest.notes || '未填写备注' }}</p><div class="inline-actions"><a-button size="small" :disabled="busy" @click="reset(latest)">载入服务器结果</a-button><a-button size="small" :disabled="busy" @click="keep">保留我的记录并使用最新版本</a-button></div></div>
    <p v-if="notice" role="status" class="small muted">{{ notice }}</p>
    <div class="inline-actions"><a-button type="primary" html-type="submit" :loading="busy" :disabled="!status">保存人工结果</a-button><span class="small muted">记录版本 {{ baseline?.manualVersion }}</span></div>
  </form>
</template>

<style scoped>
.manual-result-form { display: grid; gap: 12px; padding: 18px; background: var(--surface-subtle); border: 1px solid var(--border); border-radius: 8px; }
h4 { margin: 0; }.manual-result-form label { display: grid; gap: 7px; font-size: 13px; }
select, textarea { font: inherit; color: var(--text); border: 1px solid var(--border-strong); border-radius: 6px; padding: 9px 12px; background: var(--surface); width: 100%; box-sizing: border-box; }
textarea { resize: vertical; }.manual-conflict { background: var(--warning-soft); padding: 14px; border: 1px solid var(--warning-border); border-radius: 6px; font-size: 13px; }
.manual-notes { white-space: pre-wrap; }.inline-actions { flex-wrap: wrap; }
</style>
