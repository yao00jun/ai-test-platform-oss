<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue'
import type { Asset } from '../../api/types'

const props = defineProps<{ asset: Asset; disabled?: boolean }>()
const emit = defineEmits<{ rename: [name: string]; open: [] }>()
const editing = ref(false)
const draft = ref('')
const input = ref<{ focus: () => void }>()
let clickTimer: ReturnType<typeof setTimeout> | undefined

async function edit() {
  clearTimeout(clickTimer)
  if (props.disabled) return
  draft.value = props.asset.name
  editing.value = true
  await nextTick()
  input.value?.focus()
}
function open() {
  clearTimeout(clickTimer)
  clickTimer = setTimeout(() => { if (!editing.value) emit('open') }, 500)
}
function commit(event?: KeyboardEvent) {
  if (event?.isComposing || !editing.value || !draft.value.trim()) return
  editing.value = false
  if (draft.value.trim() !== props.asset.name) emit('rename', draft.value.trim())
}
watch(() => props.asset.id, () => { editing.value = false; clearTimeout(clickTimer) })
onUnmounted(() => clearTimeout(clickTimer))
</script>

<template>
  <a-input v-if="editing" ref="input" v-model="draft" size="small" :max-length="255" :input-attrs="{ 'aria-label': `重命名 ${asset.name}` }" @keydown.enter="commit" @keydown.esc="editing = false" @blur="commit()" @click.stop />
  <button v-else class="asset-name" type="button" title="单击打开，双击重命名" :disabled="disabled" @click.stop="open" @dblclick.stop="edit" @keydown.f2.prevent="edit" @keydown.enter.prevent="emit('open')">{{ asset.name }}</button>
</template>
