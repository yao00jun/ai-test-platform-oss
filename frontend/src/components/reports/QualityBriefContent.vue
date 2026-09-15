<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { Asset } from '../../api/types'
import { isRecord } from '../../api/client'
import { runLabels } from '../../api/runs'
import { formatTime } from '../../core/format'
import SafeRichText from '../common/SafeRichText.vue'

const RunHistoryDrawer = defineAsyncComponent(() => import('../execution/RunHistoryDrawer.vue'))
const props = defineProps<{ asset: Asset }>()
const historyVisible = ref(false)
const metrics = computed(() => isRecord(props.asset.data.metrics) && props.asset.data.metrics.schemaVersion === 'aitest.quality-metrics/v1' ? props.asset.data.metrics : undefined)
const statuses = computed(() => isRecord(metrics.value?.itemStatuses) ? metrics.value.itemStatuses : {})
const duration = computed(() => isRecord(metrics.value?.duration) ? metrics.value.duration : {})
const failures = computed(() => Array.isArray(metrics.value?.failures) ? metrics.value.failures.filter(isRecord) : [])
const runId = computed(() => typeof props.asset.data.runId === 'string' ? props.asset.data.runId : '')
const rate = computed(() => typeof metrics.value?.passRatePercent === 'number' ? `${metrics.value.passRatePercent}%` : '暂无样本')
watch(() => [props.asset.projectId, props.asset.id, runId.value], () => { historyVisible.value = false })
function time(value: unknown) { return typeof value === 'string' ? formatTime(value) : '—' }
</script>

<template>
  <section class="quality-brief" role="region" aria-label="质量简报预览">
    <header><strong>质量简报</strong><a-tag v-if="asset.source === 'AI'" size="small" color="arcoblue">AI 分析 · 待核验</a-tag></header>
    <template v-if="metrics">
      <p class="small muted">{{ metrics.scope === 'RUN' ? '当前运行的统计快照' : metrics.scope === 'DATE_RANGE' ? '指定日期范围的统计快照' : '最近 24 小时的统计快照' }} · 采集于 {{ time(metrics.capturedAt) }}</p>
      <p v-if="metrics.scope !== 'RUN'" class="small muted">范围：{{ time(metrics.from) }} 至 {{ time(metrics.to) }}</p>
      <div class="brief-metrics"><div><span>通过率</span><strong aria-label="通过率">{{ rate }}</strong></div><div><span>运行项</span><strong aria-label="统计运行项">{{ metrics.itemCount }}</strong></div><div><span>去重用例</span><strong>{{ metrics.caseCount }}</strong></div><div><span>数据行</span><strong>{{ metrics.dataRowCount }}</strong></div></div>
      <p class="small muted">通过率分母包含全部运行项，包括失败、待人工、跳过及取消。后续运行结果变化不会改写这份快照。</p>
      <div class="brief-states"><a-tag v-for="(count, status) in statuses" :key="status" size="small">{{ runLabels[status] ?? status }} {{ count }}</a-tag></div>
      <p class="small muted">{{ Number(duration.sampleCount) > 0 ? `实际耗时样本 ${duration.sampleCount} 项 · 平均 ${duration.averageMs} ms · P95 ${duration.p95Ms} ms` : '没有实际执行耗时样本。' }}</p>
      <details v-if="failures.length"><summary>失败观察 · {{ metrics.failureCount }} 项{{ metrics.failuresTruncated ? '（仅展示部分样本）' : '' }}</summary><ul><li v-for="failure in failures" :key="String(failure.runItemId)"><strong>{{ failure.caseName }}</strong> · {{ runLabels[String(failure.status)] ?? failure.status }}<p v-if="failure.error">{{ failure.error }}</p></li></ul></details>
    </template>
    <p v-else class="small muted">此简报没有运行统计快照。可从执行记录生成基于实际结果的分析。</p>
    <div class="brief-content"><h3>分析正文</h3><SafeRichText :value="String(asset.data.content ?? '')" /></div>
    <a-button v-if="runId" type="text" size="small" :aria-label="`查看来源运行 ${runId}`" @click="historyVisible = true">查看来源运行</a-button>
    <details v-if="metrics"><summary>保存的统计数据</summary><pre class="json-view">{{ JSON.stringify(metrics, null, 2) }}</pre></details>
    <RunHistoryDrawer v-if="historyVisible && runId" v-model:visible="historyVisible" :project-id="asset.projectId" :initial-run-id="runId" />
  </section>
</template>

<style scoped>
.quality-brief { min-width: 0; margin-bottom: 24px; padding: 18px; border: 1px solid var(--border); border-radius: 8px; background: #f8f9fd; overflow-wrap: anywhere; }.quality-brief header { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }.brief-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-block: 16px; }.brief-metrics span { display: block; font-size: 12px; color: var(--muted); }.brief-metrics strong { font-size: 24px; font-weight: 600; }.brief-states { display: flex; gap: 8px; flex-wrap: wrap; }.brief-content { border-block-start: 1px solid var(--border); padding-top: 14px; margin-block: 14px; }.brief-content h3 { font-size: 13px; margin: 0 0 8px; }.quality-brief details { margin-top: 12px; font-size: 12px; }.quality-brief summary { cursor: pointer; color: var(--muted); }.quality-brief ul { padding-left: 20px; }.quality-brief li { margin-block: 10px; }.quality-brief pre { max-height: 300px; overflow: auto; }@media (max-width: 560px) { .brief-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
