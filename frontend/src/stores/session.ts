import { computed, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { http } from '../api/client'
import { PlatformSession, type SessionState } from '../core/platform-session'
import { useWorkspaceStore } from './workspace'

export const useSessionStore = defineStore('platform-session', () => {
  const state = shallowRef<SessionState>()
  const workspace = useWorkspaceStore()
  const controller = new PlatformSession(http, next => {
    if (state.value && next.workspaceGeneration !== state.value.workspaceGeneration) workspace.clearSession()
    state.value = next
  })
  state.value = controller.state
  return {
    state: computed(() => state.value ?? controller.state),
    refresh: () => controller.refresh(),
    login: (username: string, password: string) => controller.login(username, password),
    logout: () => controller.logout(),
  }
})
