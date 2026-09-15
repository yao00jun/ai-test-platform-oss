<script setup lang="ts">
/*
 * Route-selected menu and collapse interaction adapted from MeterSphere:
 * frontend/src/components/business/ms-menu/index.vue and use-menu-tree.ts.
 * Copyright (c) 2026-present FIT2CLOUD. GPLv3 with additional terms;
 * upstream notices are preserved in frontend/THIRD_PARTY_NOTICES.md.
 * Adaptation: the approved fixed route set replaces permission/org stores;
 * Vue Router's route ref replaces MeterSphere's route-listener utility.
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconApps, IconFolder, IconCheckCircle, IconCode, IconBranch, IconDesktop, IconCalendar, IconBug, IconSettings, IconMenuFold, IconMenuUnfold, IconExport } from '@arco-design/web-vue/es/icon'
import { navigation } from '../../router'

const props = defineProps<{ collapsed: boolean; mobile: boolean; authEnabled?: boolean; username?: string | null; signingOut?: boolean }>()
const emit = defineEmits<{ 'update:collapsed': [value: boolean]; navigate: []; settings: []; logout: [] }>()
const route = useRoute()
const router = useRouter()
const selectedKey = ref<string[]>([])
const menuCollapsed = computed(() => props.collapsed && !props.mobile)
const icons = { apps: IconApps, folder: IconFolder, check: IconCheckCircle, code: IconCode, branch: IconBranch, desktop: IconDesktop, calendar: IconCalendar, bug: IconBug }

watch(() => route.name, (name) => { selectedKey.value = [String(name ?? 'workspace')] }, { immediate: true })
function goto(name: string) {
  if (route.name !== name) void router.push({ name })
  emit('navigate')
}
</script>

<template>
  <aside class="app-sidebar" aria-label="应用导航">
    <RouterLink to="/" class="brand" aria-label="AI 测试平台，返回工作台" @click="emit('navigate')">
      <span class="brand-symbol"><IconCheckCircle /></span>
      <span class="brand-name">AI 测试平台</span>
    </RouterLink>
    <div class="sidebar-caption">测试工作空间</div>
    <nav class="sidebar-navigation" aria-label="主要功能">
      <a-menu :selected-keys="selectedKey" :collapsed="menuCollapsed" :auto-open-selected="true" role="menu" @menu-item-click="goto">
        <a-menu-item v-for="item in navigation" :key="item.name" :title="item.label" role="menuitem" :tabindex="0" @keydown.enter="goto(item.name)" @keydown.space.prevent="goto(item.name)">
          <template #icon><component :is="icons[item.icon]" /></template>
          {{ item.label }}
        </a-menu-item>
      </a-menu>
    </nav>
    <div class="sidebar-bottom">
      <button v-if="authEnabled" class="sidebar-action session-logout" type="button" aria-label="退出登录" :title="`当前账号：${username}`" :disabled="signingOut" @click="emit('logout')"><IconExport /><span>退出登录 · {{ username }}</span></button>
      <button class="sidebar-action" type="button" aria-label="模型设置" @click="emit('settings')"><IconSettings /><span>模型设置</span></button>
      <button class="sidebar-action sidebar-collapse-action" type="button" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="emit('update:collapsed', !collapsed)">
        <IconMenuUnfold v-if="collapsed" /><IconMenuFold v-else /><span>收起导航</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.session-logout span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
