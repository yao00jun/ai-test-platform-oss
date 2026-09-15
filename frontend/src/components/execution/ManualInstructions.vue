<script setup lang="ts">
import type { Asset } from '../../api/types'
import { displayValue } from '../../core/format'
import CaseContent from '../cases/CaseContent.vue'

defineProps<{ name: string; asset?: Asset; steps: Asset[] }>()
</script>

<template>
  <section :aria-label="`执行说明 ${name}`" class="manual-instructions">
    <h4>执行说明 <span class="small muted">运行时版本 {{ asset?.version ?? '未知' }}</span></h4>
    <CaseContent v-if="asset?.type === 'FUNCTIONAL_CASE'" :asset="asset" :steps="steps" table-label="人工测试步骤" />
    <template v-else-if="asset"><p class="small muted">提交运行时的请求与配置</p><pre class="json-view">{{ displayValue(asset.data) }}</pre></template>
    <p v-else class="small muted">此历史记录未包含该资产的执行说明。</p>
  </section>
</template>

<style scoped>
.manual-instructions { margin-bottom: 18px; }h4 { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 0 0 16px; }h5 { margin: 14px 0 6px; font-size: 12px; }
.instruction-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }.asset-table { min-width: 400px; table-layout: fixed; }.asset-table th:first-child { width: 40px; }.json-view { max-height: 400px; overflow: auto; }
</style>
