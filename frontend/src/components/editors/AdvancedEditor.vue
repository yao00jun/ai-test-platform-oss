<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { IconCode } from '@arco-design/web-vue/es/icon'

const props = defineProps<{ modelValue: string; language: 'json' | 'sql' | 'code'; label: string; inputId?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const useMonaco = ref(false)
const MonacoEditor = defineAsyncComponent(() => import('./MonacoEditor.vue'))
const jsonError = computed(() => {
  if (props.language !== 'json' || !props.modelValue.trim()) return ''
  try { JSON.parse(props.modelValue); return '' } catch (error) { return error instanceof Error ? `JSON 格式无效：${error.message}` : 'JSON 格式无效' }
})
</script>

<template>
  <div style="width: 100%">
    <div class="editor-wrap">
      <div class="editor-bar"><span>{{ language.toUpperCase() }}</span><a-button type="text" size="mini" @click="useMonaco = !useMonaco"><template #icon><IconCode /></template>{{ useMonaco ? '文本编辑' : '代码编辑器' }}</a-button></div>
      <MonacoEditor v-if="useMonaco" :model-value="modelValue" :language="language === 'code' ? 'javascript' : language" :label="label" @update:model-value="emit('update:modelValue', $event)" />
      <a-textarea v-else :model-value="modelValue" :textarea-attrs="{ id: inputId, 'aria-label': label, spellcheck: false }" :auto-size="{ minRows: 5, maxRows: 12 }" @update:model-value="emit('update:modelValue', $event)" />
    </div>
    <div v-if="jsonError" class="editor-error" role="alert">{{ jsonError }}</div>
  </div>
</template>
