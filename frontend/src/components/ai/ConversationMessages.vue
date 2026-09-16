<script setup lang="ts">
/*
 * Message placement, edit-to-reuse feedback and scroll-to-latest interaction
 * adapted from MeterSphere ms-ai-drawer/components/conversation.vue.
 * Copyright (c) 2026-present FIT2CLOUD. See THIRD_PARTY_NOTICES.md.
 * Adaptation removes Element Bubble/Typewriter and renders untrusted content
 * as text, using the backend's persisted role/status/version fields.
 */
import { computed, nextTick, ref, watch } from 'vue'
import { IconCopy, IconEdit, IconRobot } from '@arco-design/web-vue/es/icon'
import { Message } from '@arco-design/web-vue'
import type { ConversationMessage } from '../../api/types'
import { formatTime } from '../../core/format'

const props = defineProps<{ messages: ConversationMessage[]; answering: boolean; streamedText: string }>()
const emit = defineEmits<{ reuse: [text: string] }>()
const list = ref<HTMLDivElement>()
const conversationItems = computed(() => props.messages.map((item) => ({ ...item, placement: item.role.toLowerCase() === 'assistant' ? 'start' : 'end' })))
async function copy(text: string) {
  try { await navigator.clipboard.writeText(text); Message.success('已复制') } catch { Message.error('复制失败，请手动选择文本复制') }
}
function handleEdit(item: ConversationMessage) { emit('reuse', item.content) }
watch(() => [props.messages.length, props.streamedText], async () => {
  await nextTick()
  list.value?.scrollTo({ top: list.value.scrollHeight, behavior: 'instant' })
})
</script>

<template>
  <div ref="list" class="conversation-messages" role="log" aria-live="polite" aria-label="AI 对话记录">
    <div v-if="!messages.length && !answering" class="conversation-empty"><IconRobot /><h3>围绕当前记录继续完善</h3><p>描述需要补充的边界条件、预期结果或修改方式。每一轮反馈都会保留在会话中。</p></div>
    <article v-for="item in conversationItems" :key="item.id" class="conversation-message" :class="`message-${item.placement}`">
      <div class="message-meta"><strong>{{ item.role.toLowerCase() === 'user' ? '你' : item.role.toLowerCase() === 'assistant' ? 'AI 助手' : item.role }}</strong><span>{{ formatTime(item.createdAt) }}</span></div>
      <div class="message-content">{{ item.content }}</div>
      <div class="message-footer"><span v-if="item.appliedVersion" class="applied-version">已应用至 v{{ item.appliedVersion }}</span><span v-else class="muted">{{ item.status }}</span><a-button type="text" size="mini" aria-label="复制消息" @click="copy(item.content)"><IconCopy /></a-button><a-button v-if="item.role.toLowerCase() === 'user'" type="text" size="mini" :disabled="answering" aria-label="复用这条反馈" @click="handleEdit(item)"><IconEdit /></a-button></div>
      <details v-if="item.validation" class="message-extra"><summary>校验信息</summary><pre class="json-view">{{ JSON.stringify(item.validation, null, 2) }}</pre></details>
      <details v-if="item.candidate" class="message-extra"><summary>查看生成结果</summary><pre class="json-view">{{ JSON.stringify(item.candidate, null, 2) }}</pre></details>
    </article>
    <article v-if="answering" class="conversation-message message-start"><div class="message-meta"><strong>AI 助手</strong><a-spin :size="12" /></div><div v-if="streamedText" class="message-content">{{ streamedText }}</div><p v-else class="small muted">任务处理中，正在等待模型响应…</p></article>
  </div>
</template>

<style scoped>
.conversation-messages { flex: 1; min-height: 160px; overflow-y: auto; padding: 4px 2px 20px; }
.conversation-empty { min-height: 250px; display: flex; flex-direction: column; justify-content: center; align-items: center; text-align: center; gap: 14px; padding: 30px; }
.conversation-empty > .arco-icon { color: var(--primary); font-size: 32px; }
.conversation-empty p { color: var(--muted); font-size: 13px; line-height: 1.9; }
.conversation-message { margin-top: 24px; }
.message-end { margin-left: 42px; }
.message-start { margin-right: 16px; }
.message-meta { display: flex; align-items: center; gap: 12px; font-size: 11px; color: var(--text-subtle); margin-bottom: 8px; }
.message-meta strong { color: var(--text-secondary); font-weight: 500; }
.message-content { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.85; }
.message-end .message-content { padding: 13px 16px; border-radius: 10px 0 10px 10px; background: var(--primary-soft); color: var(--primary-soft-text); }
.message-footer { display: flex; align-items: center; gap: 7px; font-size: 11px; margin-top: 5px; }
.applied-version { color: var(--success); }
.message-extra { font-size: 12px; color: var(--muted); margin-top: 8px; }
</style>
