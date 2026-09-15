<script setup lang="ts">
import type { Asset } from '../../api/types'
import SafeRichText from '../common/SafeRichText.vue'
defineProps<{ asset: Asset; steps: Asset[]; tableLabel?: string }>()
</script>

<template>
  <div class="case-content">
    <h5>前置条件</h5><SafeRichText :value="String(asset.data.precondition || '未填写前置条件')" />
    <div v-if="steps.length" class="table-overflow"><table class="asset-table" :aria-label="tableLabel ?? '测试步骤与预期'"><thead><tr><th>顺序</th><th>测试步骤</th><th>预期结果</th></tr></thead><tbody><tr v-for="(step, index) in steps" :key="step.id"><td>{{ index + 1 }}</td><td><SafeRichText :value="String(step.data.step ?? '')" /></td><td><SafeRichText :value="String(step.data.expected || '未填写预期结果')" /></td></tr></tbody></table></div>
    <p v-else class="small muted">暂无测试步骤。</p>
    <template v-if="asset.data.remark"><h5>备注</h5><SafeRichText :value="String(asset.data.remark)" /></template>
  </div>
</template>

<style scoped>
h5 { margin: 12px 0 6px; font-size: 12px; }.table-overflow { margin-top: 14px; }.asset-table { min-width: 400px; table-layout: fixed; }.asset-table th:first-child { width: 40px; }.asset-table td { vertical-align: top; }
</style>
