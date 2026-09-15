<script setup lang="ts">
import { computed } from 'vue'
import { ApiError, errorMessage } from '../../api/client'

const props = defineProps<{ error: unknown; retry?: boolean }>()
defineEmits<{ retry: [] }>()
const apiError = computed(() => props.error instanceof ApiError ? props.error : undefined)
</script>

<template>
  <a-alert v-if="error" type="error" :title="errorMessage(error)" role="alert">
    <template #default>
      <p v-if="apiError?.status === 409" class="small">记录已被修改。请刷新当前记录，核对最新内容后再次提交。</p>
      <details v-if="apiError?.details" class="small">
        <summary>查看问题详情</summary>
        <pre class="error-details">{{ JSON.stringify(apiError.details, null, 2) }}</pre>
      </details>
      <p v-if="apiError?.requestId" class="small">请求编号：{{ apiError.requestId }}</p>
      <a-button v-if="retry" type="text" size="small" @click="$emit('retry')">重新加载</a-button>
    </template>
  </a-alert>
</template>
