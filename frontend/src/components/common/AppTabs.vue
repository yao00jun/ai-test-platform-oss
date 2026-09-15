<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ modelValue: string; items: { value: string; label: string }[]; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLDivElement>()
function keydown(event: KeyboardEvent, index: number) {
  let next: number
  if (event.key === 'ArrowRight') next = (index + 1) % props.items.length
  else if (event.key === 'ArrowLeft') next = (index - 1 + props.items.length) % props.items.length
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = props.items.length - 1
  else return
  event.preventDefault()
  const item = props.items[next]
  if (item) { emit('update:modelValue', item.value); host.value?.querySelectorAll<HTMLButtonElement>('button')[next]?.focus() }
}
</script>

<template>
  <div ref="host" class="app-tabs" role="tablist" :aria-label="label">
    <button v-for="(item, index) in items" :key="item.value" role="tab" type="button" :aria-selected="modelValue === item.value" :tabindex="modelValue === item.value ? 0 : -1" @click="emit('update:modelValue', item.value)" @keydown="keydown($event, index)">{{ item.label }}</button>
  </div>
</template>

<style scoped>
.app-tabs { display: flex; gap: 26px; padding: 0 24px; border-bottom: 1px solid var(--border); overflow-x: auto; scrollbar-width: thin; }
.app-tabs button { position: relative; border: 0; background: none; color: #68788e; font-size: 13px; white-space: nowrap; padding: 16px 0 15px; cursor: pointer; }
.app-tabs button[aria-selected="true"] { color: var(--primary); font-weight: 600; }
.app-tabs button[aria-selected="true"]::after { position: absolute; content: ''; height: 2px; inset: auto 0 0; background: var(--primary); }
</style>
