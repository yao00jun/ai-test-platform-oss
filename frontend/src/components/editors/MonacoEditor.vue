<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor/editor/editor.api'
import 'monaco-editor/languages/definitions/sql/register'
import 'monaco-editor/languages/definitions/javascript/register'
import 'monaco-editor/language/json/monaco.contribution'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/language/json/json.worker?worker'

const props = defineProps<{ modelValue: string; language: string; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLDivElement>()
let editor: monaco.editor.IStandaloneCodeEditor | undefined
let subscription: monaco.IDisposable | undefined

self.MonacoEnvironment = { getWorker: (_workerId, label) => label === 'json' ? new JsonWorker() : new EditorWorker() }
onMounted(() => {
  if (!host.value) return
  editor = monaco.editor.create(host.value, {
    value: props.modelValue, language: props.language, automaticLayout: true, minimap: { enabled: false },
    fontSize: 12, lineHeight: 21, scrollBeyondLastLine: false, wordWrap: 'on', padding: { top: 10 },
    ariaLabel: props.label, accessibilitySupport: 'on', tabSize: 2, renderLineHighlight: 'none',
  })
  subscription = editor.onDidChangeModelContent(() => emit('update:modelValue', editor?.getValue() ?? ''))
})
watch(() => props.modelValue, (value) => { if (editor && editor.getValue() !== value) editor.setValue(value) })
onUnmounted(() => { subscription?.dispose(); const model = editor?.getModel(); editor?.dispose(); model?.dispose() })
</script>

<template><div ref="host" style="height: 240px; width: 100%" /></template>
