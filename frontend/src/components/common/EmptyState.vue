<script setup lang="ts">
import { IconFolder } from '@arco-design/web-vue/es/icon'
defineProps<{ title: string; description?: string; action?: string }>()
defineEmits<{ action: [] }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon" aria-hidden="true"><IconFolder /></div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <a-button v-if="action" type="primary" @click="$emit('action')">{{ action }}</a-button>
    <slot />
  </div>
</template>
