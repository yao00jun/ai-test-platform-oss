<script setup lang="ts">
import { computed, ref } from 'vue'
import { IconEdit, IconFolder } from '@arco-design/web-vue/es/icon'
import type { Asset } from '../../api/types'
import { moduleRows } from '../../core/module-tree'

const props = defineProps<{ modules: Asset[]; selected: string; busy?: boolean }>()
const emit = defineEmits<{ select: [id: string]; edit: [asset: Asset]; manage: [] }>()
const query = ref('')
const rows = computed(() => moduleRows(props.modules).filter(row => !query.value.trim() || row.path.toLowerCase().includes(query.value.trim().toLowerCase())))
</script>

<template>
  <nav class="module-navigation" aria-label="用例分类">
    <div class="module-heading"><strong>用例分类</strong><a-button type="text" size="mini" @click="emit('manage')">管理模块</a-button></div>
    <a-input v-model="query" :input-attrs="{ 'aria-label': '搜索模块' }" placeholder="搜索模块" allow-clear size="small" />
    <div class="module-list">
      <button type="button" class="module-filter" :class="{ selected: selected === 'ALL' }" :aria-current="selected === 'ALL' ? 'true' : undefined" @click="emit('select', 'ALL')">全部用例</button>
      <button type="button" class="module-filter" :class="{ selected: selected === 'ROOT' }" :aria-current="selected === 'ROOT' ? 'true' : undefined" @click="emit('select', 'ROOT')">未分类</button>
      <div v-for="row in rows" :key="row.asset.id" class="module-row" :class="{ selected: selected === row.asset.id }" :style="{ paddingLeft: `${Math.min(row.depth, 8) * 12}px` }">
        <button type="button" class="module-filter" :title="row.path" :aria-label="`筛选模块 ${row.asset.name}`" :aria-current="selected === row.asset.id ? 'true' : undefined" @click="emit('select', row.asset.id)"><IconFolder /><span>{{ row.asset.name }}</span></button>
        <a-button type="text" size="mini" :disabled="busy" :aria-label="`编辑模块 ${row.asset.name}`" @click="emit('edit', row.asset)"><IconEdit /></a-button>
      </div>
      <p v-if="!modules.length" class="small muted">从“管理模块”创建分类，也可先编写未分类用例。</p>
      <p v-else-if="!rows.length" class="small muted">没有匹配的模块。</p>
    </div>
    <p class="module-hint small muted">选择模块查看直属用例；子模块独立选择。</p>
  </nav>
</template>

<style scoped>
.module-navigation { min-width: 0; padding: 16px 12px; border-right: 1px solid var(--border); }
.module-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; gap: 6px; font-size: 13px; }
.module-list { margin-top: 10px; max-height: 560px; overflow: auto; }.module-filter { display: flex; gap: 7px; align-items: center; border: 0; background: none; font: inherit; font-size: 12px; padding: 9px 8px; width: 100%; text-align: left; color: var(--text); cursor: pointer; border-radius: 5px; min-width: 0; }
.module-filter span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.module-row { display: flex; align-items: center; border-radius: 5px; }.module-row .module-filter { flex: 1; }.selected { background: #eeedff; color: var(--primary); }.module-filter:hover { background: var(--color-fill-1); }.module-hint { margin: 14px 0 0; line-height: 1.7; }
@media (max-width: 960px) { .module-navigation { border-right: 0; border-bottom: 1px solid var(--border); }.module-list { max-height: 180px; } }
</style>
