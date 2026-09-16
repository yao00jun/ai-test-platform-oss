<script setup lang="ts">
import { computed } from 'vue'
import { IconMoon, IconSun } from '@arco-design/web-vue/es/icon'
import { useTheme } from '../../core/theme'

const { theme, toggle } = useTheme()
const label = computed(() => theme.value === 'dark' ? '切换到浅色主题' : '切换到暗色主题')
</script>

<template>
  <button type="button" class="theme-toggle" :aria-label="label" :title="label" @click="toggle">
    <IconSun v-if="theme === 'dark'" aria-hidden="true" />
    <IconMoon v-else aria-hidden="true" />
  </button>
</template>

<style scoped>
.theme-toggle { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 34px; width: 34px; height: 34px; padding: 0; border: 1px solid var(--border); border-radius: 7px; color: var(--text-secondary); background: var(--surface); cursor: pointer; }
.theme-toggle:hover { color: var(--primary); background: var(--primary-soft); border-color: var(--primary-border); }
.theme-toggle .arco-icon { font-size: 18px; }
</style>
