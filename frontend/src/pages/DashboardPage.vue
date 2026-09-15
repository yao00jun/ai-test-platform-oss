<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { IconArrowRight, IconRefresh } from '@arco-design/web-vue/es/icon'
import { assetApi } from '../api/assets'
import type { ProjectSummary } from '../api/types'
import { useWorkspaceStore } from '../stores/workspace'
import { RequestScope } from '../core/request-scope'
import EmptyState from '../components/common/EmptyState.vue'
import ErrorNotice from '../components/common/ErrorNotice.vue'
import AssetWorkspace from '../components/assets/AssetWorkspace.vue'
import RunHistoryDrawer from '../components/execution/RunHistoryDrawer.vue'
import PipelineWorkspace from '../components/pipeline/PipelineWorkspace.vue'
import EvalOpsPanel from '../components/workbench/EvalOpsPanel.vue'
import DashboardCards from '../components/workbench/DashboardCards.vue'
import MorningBriefPanel from '../components/workbench/MorningBriefPanel.vue'

const workspace = useWorkspaceStore()
const summary = ref<ProjectSummary>()
const loading = ref(false)
const error = ref<unknown>()
const scope = new RequestScope()
const runHistoryVisible = ref(false), selectedRunId = ref('')
function showRun(id: string) { selectedRunId.value = id; runHistoryVisible.value = true }

async function load() {
  if (!workspace.selectedProjectId) return
  const token = scope.begin(workspace.selectedProjectId)
  loading.value = true
  error.value = undefined
  try {
    const result = await assetApi.summary(workspace.selectedProjectId, token.signal)
    if (scope.isCurrent(token)) summary.value = result
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
watch(() => workspace.selectedProjectId, () => { scope.invalidate(); summary.value = undefined; loading.value = false; runHistoryVisible.value = false; selectedRunId.value = ''; void load() }, { immediate: true })
watch(() => workspace.activityRevision, () => { void load() })
onUnmounted(() => scope.invalidate())
</script>

<template>
  <div>
    <div class="page-header"><div><h1>工作台</h1><p class="page-description">{{ workspace.currentProject ? `${workspace.currentProject.name}的测试资产与最近活动。` : '选择项目，查看测试资产与最近活动。' }}</p></div><div v-if="workspace.selectedProjectId" class="page-actions"><a-button :loading="loading" @click="load"><template #icon><IconRefresh /></template>刷新概览</a-button><a-button type="primary" @click="$router.push('/cases')">管理测试用例<IconArrowRight /></a-button></div></div>
    <div v-if="!workspace.selectedProjectId" class="surface"><EmptyState title="开始一个新的测试项目" description="把需求、接口、场景与缺陷放在同一个工作空间，让测试过程可追溯。" action="创建项目" @action="$router.push('/projects')" /></div>
    <template v-else>
      <ErrorNotice :error="error" retry style="margin-bottom: 24px" @retry="load" />
      <PipelineWorkspace :project-id="workspace.selectedProjectId" />
      <DashboardCards :project-id="workspace.selectedProjectId" :summary="summary" :revision="workspace.activityRevision" @changed="workspace.recordActivity" @open-run="showRun" />
      <EvalOpsPanel :project-id="workspace.selectedProjectId" />
      <MorningBriefPanel :project-id="workspace.selectedProjectId" @changed="workspace.recordActivity" />
      <div v-if="loading && !summary" class="loading-block"><a-spin tip="加载项目概览…" /></div>
      <AssetWorkspace embedded title="看板与质量简报" description="维护项目看板配置与质量说明，所有内容均保存在当前项目。" :types="['QUALITY_BRIEF', 'DASHBOARD']" @changed="workspace.recordActivity" />
      <RunHistoryDrawer v-model:visible="runHistoryVisible" :project-id="workspace.selectedProjectId" :initial-run-id="selectedRunId" />
    </template>
  </div>
</template>
