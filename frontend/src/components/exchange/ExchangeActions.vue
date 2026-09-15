<script setup lang="ts">
import { ref } from 'vue'
import { IconDownload, IconImport, IconExport } from '@arco-design/web-vue/es/icon'
import type { AssetType } from '../../api/types'
import ExchangeDrawer from './ExchangeDrawer.vue'

defineProps<{ projectId: string; type: AssetType; parentId?: string; parents?: { label: string; value: string }[]; selectedIds?: string[] }>()
const emit = defineEmits<{ imported: [ids: string[]] }>()
const visible = ref(false)
const mode = ref<'import' | 'export' | 'templates'>('import')
function open(value: typeof mode.value) { mode.value = value; visible.value = true }
</script>

<template>
  <div class="inline-actions exchange-actions">
    <a-button :disabled="!projectId" @click="open('import')"><template #icon><IconImport /></template>导入</a-button>
    <a-button :disabled="!projectId" @click="open('export')"><template #icon><IconExport /></template>导出<span v-if="selectedIds?.length"> · {{ selectedIds.length }}</span></a-button>
    <a-button @click="open('templates')"><template #icon><IconDownload /></template>导入模板</a-button>
    <ExchangeDrawer v-model:visible="visible" :project-id="projectId" :type="type" :initial-mode="mode" :parent-id="parentId" :parents="parents" :selected-ids="selectedIds" @imported="emit('imported', $event)" />
  </div>
</template>
