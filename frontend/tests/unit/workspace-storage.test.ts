import { createPinia } from 'pinia'
import { afterEach, expect, it, vi } from 'vitest'
import { useWorkspaceStore } from '../../src/stores/workspace'

afterEach(() => vi.unstubAllGlobals())

it('allows the login gate and workspace to initialize when browser persistence is blocked', () => {
  vi.stubGlobal('localStorage', {
    getItem() { throw new Error('SecurityError') },
    setItem() { throw new Error('SecurityError') },
    removeItem() { throw new Error('SecurityError') },
  })
  const workspace = useWorkspaceStore(createPinia())
  expect(workspace.selectedProjectId).toBe('')
  workspace.selectProject('project-a')
  expect(workspace.selectedProjectId).toBe('project-a')
  workspace.clearSession()
  expect(workspace.selectedProjectId).toBe('')
})
