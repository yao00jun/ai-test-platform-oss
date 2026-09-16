<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IconMenu, IconRight } from '@arco-design/web-vue/es/icon'
import { Message } from '@arco-design/web-vue'
import AppSidebar from '../components/layout/AppSidebar.vue'
import ThemeToggle from '../components/layout/ThemeToggle.vue'
import ModelSettingsDrawer from '../components/settings/ModelSettingsDrawer.vue'
import ErrorNotice from '../components/common/ErrorNotice.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { useWorkspaceStore } from '../stores/workspace'
import { useSessionStore } from '../stores/session'
import { errorMessage } from '../api/client'
import { navigation } from '../router'

const workspace = useWorkspaceStore()
const session = useSessionStore()
const route = useRoute()
const collapsed = ref(false)
try { collapsed.value = localStorage.getItem('ai-test-platform:collapsed') === 'true' }
catch { /* The navigation remains usable if storage is restricted. */ }
const mobileOpen = ref(false)
const media = window.matchMedia('(max-width: 768px)')
const mobile = ref(media.matches)
const settingsVisible = ref(false)
const logoutVisible = ref(false)
const currentLabel = computed(() => navigation.find((item) => item.name === route.name)?.label ?? '工作台')
const onMediaChange = (event: MediaQueryListEvent) => { mobile.value = event.matches; mobileOpen.value = false }
const onEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') mobileOpen.value = false }
function confirmLogout() {
  mobileOpen.value = false
  logoutVisible.value = true
}
async function signOut() {
  try { await session.logout(); return true }
  catch (error) { Message.error(errorMessage(error)); return false }
}

watch(collapsed, (value) => { try { localStorage.setItem('ai-test-platform:collapsed', String(value)) } catch { /* Session state is sufficient. */ } })
onMounted(() => { void workspace.bootstrap(); media.addEventListener('change', onMediaChange); document.addEventListener('keydown', onEscape) })
onUnmounted(() => { media.removeEventListener('change', onMediaChange); document.removeEventListener('keydown', onEscape) })
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed, 'mobile-nav-open': mobileOpen }">
    <AppSidebar v-model:collapsed="collapsed" :mobile="mobile" :inert="mobile && !mobileOpen" :aria-hidden="mobile && !mobileOpen" :auth-enabled="session.state.enabled" :username="session.state.username" :signing-out="session.state.busy" @navigate="mobileOpen = false" @settings="settingsVisible = true; mobileOpen = false" @logout="confirmLogout" />
    <button v-if="mobileOpen" class="mobile-backdrop" aria-label="关闭导航" @click="mobileOpen = false" />
    <div class="app-body">
      <header class="app-header">
        <a-button class="mobile-menu-button" type="text" aria-label="打开导航" @click="mobileOpen = true"><IconMenu /></a-button>
        <div class="header-breadcrumb"><span>测试空间</span><IconRight /><strong>{{ currentLabel }}</strong></div>
        <div class="project-switcher">
          <span class="project-switcher-label">当前项目</span>
          <a-select :model-value="workspace.selectedProjectId || undefined" placeholder="请选择项目" :loading="workspace.loading" aria-label="当前项目" :options="workspace.projects.map((item) => ({ label: item.name, value: item.id }))" @change="workspace.selectProject(String($event))" />
          <RouterLink v-if="!workspace.projects.length && workspace.projectsLoaded" to="/projects" class="small">项目管理</RouterLink>
          <ThemeToggle />
        </div>
      </header>
      <main id="main-content" class="main-content">
        <ErrorNotice v-if="workspace.error" :error="new Error(workspace.error)" retry style="margin-bottom: 24px" @retry="workspace.bootstrap" />
        <div v-if="workspace.loading && !workspace.catalog.length" class="loading-block"><a-spin tip="正在连接测试工作空间…" /></div>
        <RouterView v-else-if="workspace.catalog.length && workspace.projectsLoaded" v-slot="{ Component }">
          <component :is="Component" :key="`${String(route.name)}:${workspace.selectedProjectId}`" />
        </RouterView>
        <div v-else class="surface"><EmptyState title="工作空间暂时无法连接" description="请启动后端服务后重新加载。连接恢复后会显示项目与测试资产。" action="重新连接" @action="workspace.bootstrap" /></div>
      </main>
    </div>
    <ModelSettingsDrawer v-model:visible="settingsVisible" />
    <a-modal v-model:visible="logoutVisible" title="退出登录" role="dialog" aria-modal="true" aria-label="退出登录" simple
      ok-text="退出" cancel-text="取消" :on-before-ok="signOut" :mask-closable="!session.state.busy" :esc-to-close="!session.state.busy"
      :cancel-button-props="{ disabled: session.state.busy }" unmount-on-close>
      退出将清理当前页面中未保存的编辑。已保存的资产和已提交的后台任务会保留。
    </a-modal>
  </div>
</template>
