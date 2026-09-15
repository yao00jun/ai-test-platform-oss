<script setup lang="ts">
import type { Asset } from '../../api/types'
import type { PipelineOptions } from '../../api/pipelines'
defineProps<{ environments: Asset[]; databases: Asset[]; recordings: Asset[]; disabled?: boolean; environmentLocked?: boolean }>()
const options = defineModel<PipelineOptions>({ required: true })
</script>

<template>
  <div class="pipeline-config-fields">
    <label>执行环境<select v-model="options.environmentId" aria-label="流水线执行环境" :disabled="disabled || environmentLocked"><option value="">暂不指定环境</option><option v-for="asset in environments" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label>
    <p v-if="environmentLocked" class="small muted">已有运行保留原环境；其他环境的运行可从测试计划发起。</p>
    <label>业务数据库<select v-model="options.databaseSourceIds" aria-label="流水线业务数据库" multiple :disabled="disabled" :size="Math.min(4, Math.max(2, databases.length))"><option v-for="asset in databases" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label>
    <p class="small muted">SQL 只依据已配置业务库的实际表结构生成。未选择数据源时，该阶段显示待补充。</p>
    <label>已有 UI 录制场景<select v-model="options.uiEvidenceIds" aria-label="流水线 UI 证据" multiple :disabled="disabled" :size="Math.min(4, Math.max(2, recordings.length))"><option v-for="asset in recordings" :key="asset.id" :value="asset.id">{{ asset.name }}</option></select></label>
    <p class="small muted">UI 依据环境的 Web 地址采集真实页面，或使用选择的录制场景。没有页面依据时保留缺口。</p>
    <label class="pipeline-check"><input v-model="options.execute" type="checkbox" :disabled="disabled" aria-label="生成后执行测试计划">生成后执行测试计划</label>
    <p class="small muted">功能用例进入人工执行清单；接口、SQL 和 UI 产生实际执行结果。失败后自动诊断并建立缺陷草稿。</p>
  </div>
</template>

<style scoped>
.pipeline-config-fields { display: grid; gap: 12px; }label { display: grid; gap: 7px; font-size: 13px; }select { width: 100%; padding: 9px 12px; color: var(--text); background: white; border: 1px solid var(--border); border-radius: 6px; font: inherit; }select[multiple] { min-height: 66px; }p { margin: 0 0 8px; line-height: 1.6; }.pipeline-check { display: flex; align-items: center; gap: 8px; }
</style>
