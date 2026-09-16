import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { assetApi } from '../api/assets'
import { errorMessage } from '../api/client'
import type { Asset, AssetType, CatalogType } from '../api/types'
import { RequestScope } from '../core/request-scope'

const selectedKey = 'ai-test-platform:selected-project'

export const useWorkspaceStore = defineStore('workspace', () => {
  const projects = ref<Asset[]>([])
  const catalog = ref<CatalogType[]>([])
  const selectedProjectId = ref('')
  try { selectedProjectId.value = localStorage.getItem(selectedKey) ?? '' }
  catch { /* Browser persistence is optional, including during login initialization. */ }
  const loading = ref(false)
  const projectsLoaded = ref(false)
  const activityRevision = ref(0)
  const error = ref('')
  const scope = new RequestScope()
  let sessionGeneration = 0
  const currentProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value))
  const catalogMap = computed(() => new Map(catalog.value.map((definition) => [definition.type, definition])))

  function selectProject(id: string) {
    selectedProjectId.value = id
    try { localStorage.setItem(selectedKey, id) } catch { /* Storage can be disabled; the active session still works. */ }
  }
  function applyProjects(records: Asset[]) {
    projects.value = records
    projectsLoaded.value = true
    if (!records.some((record) => record.id === selectedProjectId.value)) selectProject(records[0]?.id ?? '')
  }
  async function bootstrap() {
    const token = scope.begin('workspace')
    loading.value = true
    error.value = ''
    const results = await Promise.allSettled([assetApi.projects(token.signal), assetApi.catalog(token.signal)])
    if (!scope.isCurrent(token)) return
    const [projectResult, catalogResult] = results
    if (projectResult.status === 'fulfilled') applyProjects(projectResult.value)
    if (catalogResult.status === 'fulfilled') catalog.value = catalogResult.value.types
    error.value = [...new Set(results.filter((result): result is PromiseRejectedResult => result.status === 'rejected').map((result) => errorMessage(result.reason)))].join('；')
    loading.value = false
  }
  async function refreshProjects() {
    const generation = sessionGeneration
    const records = await assetApi.projects()
    if (generation === sessionGeneration) applyProjects(records)
  }
  function clearSession() {
    sessionGeneration++; scope.invalidate()
    projects.value = []; catalog.value = []; selectedProjectId.value = ''
    projectsLoaded.value = false; loading.value = false; activityRevision.value = 0; error.value = ''
    try { localStorage.removeItem(selectedKey) } catch { /* In-memory state was still cleared. */ }
  }
  function label(type: AssetType) { return catalogMap.value.get(type)?.label ?? type }
  function recordActivity(projectId: string) { if (projectId === selectedProjectId.value) activityRevision.value++ }

  return { projects, projectsLoaded, catalog, catalogMap, selectedProjectId, currentProject, loading, error, activityRevision, recordActivity, selectProject, bootstrap, refreshProjects, clearSession, label }
})
