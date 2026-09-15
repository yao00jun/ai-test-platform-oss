<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { IconCheckCircle } from '@arco-design/web-vue/es/icon'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const locked = computed(() => session.state.phase !== 'ready')
const gate = ref<HTMLElement>()
const username = ref('')
const password = ref('')
const hidden = new Map<HTMLElement, boolean>()
let observer: MutationObserver | undefined
let previousFocus: HTMLElement | undefined

function synchronizePortals() {
  if (!locked.value) {
    delete document.body.dataset.platformLocked
    for (const [element, inert] of hidden) element.inert = inert
    hidden.clear()
    return
  }
  document.body.dataset.platformLocked = 'true'
  // Arco drawers teleport outside #app. Keep their state while blocking both focus and pointer access.
  for (const element of document.body.children) {
    if (!(element instanceof HTMLElement) || element.id === 'app') continue
    if (!hidden.has(element)) hidden.set(element, element.inert)
    element.inert = true
  }
}
function focusGate() {
  const input = gate.value?.querySelector<HTMLElement>('input:not(:disabled), button:not(:disabled)')
  ;(input ?? gate.value)?.focus({ preventScroll: true })
}
function trapTab(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const controls = [...(gate.value?.querySelectorAll<HTMLElement>('input, button, a[href], [tabindex]') ?? [])]
    .filter(element => element.tabIndex >= 0 && !element.hasAttribute('disabled') && element.getClientRects().length > 0)
  const first = controls[0], last = controls.at(-1)
  if (!first || !last) { event.preventDefault(); gate.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === gate.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
async function signIn() {
  if (session.state.busy) return
  try { await session.login(username.value, password.value) }
  catch { /* The session state carries the actionable login error. */ }
  finally { password.value = '' }
}
function retry() { void session.refresh().catch(() => {}) }
watch(() => session.state.username, name => { if (name && !username.value) username.value = name })
watch(locked, async value => {
  if (value && document.activeElement instanceof HTMLElement) previousFocus = document.activeElement
  synchronizePortals()
  await nextTick()
  if (value) focusGate()
  else if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
}, { flush: 'post' })
watch(() => session.state.busy, async busy => { if (!busy && locked.value) { await nextTick(); focusGate() } })
onMounted(() => {
  observer = new MutationObserver(synchronizePortals)
  observer.observe(document.body, { childList: true })
  synchronizePortals()
  retry()
})
onUnmounted(() => {
  observer?.disconnect()
  delete document.body.dataset.platformLocked
  for (const [element, inert] of hidden) element.inert = inert
  hidden.clear()
})
</script>

<template>
  <div v-if="session.state.hasWorkspace" v-show="!locked" :key="session.state.workspaceGeneration" :inert="locked" :aria-hidden="locked">
    <slot />
  </div>
  <section v-if="locked" ref="gate" class="session-gate" role="dialog" aria-modal="true" aria-labelledby="session-title" :aria-busy="session.state.busy" tabindex="-1" @keydown="trapTab">
    <div class="session-card">
      <div class="session-brand"><span class="brand-symbol"><IconCheckCircle /></span><span>AI 测试平台</span></div>
      <h1 id="session-title">登录测试工作台</h1>
      <p v-if="session.state.hasWorkspace" class="session-intro">当前标签页的未保存编辑已保留，登录后可继续。</p>
      <p v-else class="session-intro">使用已配置的工作空间账号，继续你的测试工作。</p>
      <div v-if="session.state.busy && !session.state.csrfToken" class="session-loading"><a-spin tip="正在确认登录状态…" /></div>
      <template v-else-if="session.state.phase === 'error'">
        <p role="alert" class="session-error">{{ session.state.error }}</p>
        <a-button type="primary" long size="large" @click="retry">重新连接</a-button>
      </template>
      <form v-else class="session-form" @submit.prevent="signIn">
        <label for="platform-username">用户名</label>
        <input id="platform-username" v-model="username" class="session-input" name="username" autocomplete="username" maxlength="64" required :disabled="session.state.busy">
        <label for="platform-password">密码</label>
        <input id="platform-password" v-model="password" class="session-input" name="password" type="password" autocomplete="current-password" maxlength="1024" required :disabled="session.state.busy">
        <p v-if="session.state.error" role="alert" class="session-error">{{ session.state.error }}</p>
        <a-button type="primary" html-type="submit" long size="large" :loading="session.state.busy" :disabled="!session.state.csrfToken">登录</a-button>
        <a-button v-if="!session.state.csrfToken" type="text" long @click="retry">刷新登录状态</a-button>
      </form>
      <p class="session-note">AI 草稿由人裁决，每一次修改都可追溯。</p>
    </div>
  </section>
</template>

<style>
body[data-platform-locked='true'] > :not(#app),
body[data-platform-locked='true'] > :not(#app) * { visibility: hidden !important; pointer-events: none !important; }
.session-gate { position: fixed; inset: 0; z-index: 2147480000; display: grid; place-items: center; padding: 24px; overflow-y: auto; background: var(--app-bg); outline: none; }
.session-card { width: 100%; max-width: 432px; padding: 36px; margin: auto; background: var(--surface); border: 1px solid var(--border); border-radius: 14px; box-shadow: 0 14px 45px #24304d0b; }
.session-brand { display: flex; align-items: center; gap: 10px; color: var(--muted); font-size: 14px; font-weight: 600; margin-bottom: 30px; }
.session-card h1 { font-size: 24px; }
.session-intro { color: var(--muted); font-size: 13px; line-height: 1.8; margin: 12px 0 26px; }
.session-form { display: flex; flex-direction: column; gap: 9px; }
.session-form label { font-size: 13px; color: var(--text); }
.session-input { width: 100%; height: 42px; padding: 9px 12px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); margin-bottom: 11px; }
.session-input:focus { outline: 2px solid var(--primary-soft); border-color: var(--primary); }
.session-input:disabled { background: var(--app-bg); }
.session-error { color: #b4233e; font-size: 13px; line-height: 1.7; margin-bottom: 12px; overflow-wrap: anywhere; }
.session-note { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--border); font-size: 11px; color: var(--muted); }
.session-loading { min-height: 130px; display: grid; place-items: center; }
@media (max-width: 480px) { .session-gate { padding: 16px; } .session-card { padding: 28px 24px; } .session-card h1 { font-size: 22px; } }
</style>
