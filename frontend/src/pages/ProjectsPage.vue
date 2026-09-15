<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconDelete, IconEdit, IconPlus, IconRefresh, IconRobot } from '@arco-design/web-vue/es/icon'
import { useWorkspaceStore } from '../stores/workspace'
import { assetApi } from '../api/assets'
import type { Asset } from '../api/types'
import { formatTime } from '../core/format'
import AssetEditorDialog from '../components/assets/AssetEditorDialog.vue'
import AssetWorkspace from '../components/assets/AssetWorkspace.vue'
import InlineAssetName from '../components/assets/InlineAssetName.vue'
import EmptyState from '../components/common/EmptyState.vue'
import ErrorNotice from '../components/common/ErrorNotice.vue'
import ExchangeActions from '../components/exchange/ExchangeActions.vue'
import AiDrawer from '../components/ai/AiDrawer.vue'
import SourceAnalysisPanel from '../components/analysis/SourceAnalysisPanel.vue'

const workspace = useWorkspaceStore()
const projectDefinition = computed(() => workspace.catalogMap.get('PROJECT'))
const dialogVisible = ref(false)
const editing = ref<Asset>()
const busy = ref(false)
const error = ref<unknown>()
const aiVisible = ref(false)
let active = true

function create() { editing.value = undefined; dialogVisible.value = true }
function edit(asset: Asset) { editing.value = asset; dialogVisible.value = true }
async function refresh() {
  busy.value = true
  error.value = undefined
  try { await workspace.refreshProjects() } catch (failure) { if (active) error.value = failure }
  finally { if (active) busy.value = false }
}
async function rename(project: Asset, name: string) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await assetApi.patch(project.id, project.id, { baseVersion: project.version, name })
    await workspace.refreshProjects()
    if (active) Message.success('项目名称已更新')
  } catch (failure) { if (active) error.value = failure }
  finally { if (active) busy.value = false }
}
async function remove(project: Asset) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await assetApi.remove(project)
    await workspace.refreshProjects()
    Message.success('项目已删除')
  } catch (failure) { if (active) error.value = failure }
  finally { if (active) busy.value = false }
}
async function saved(project: Asset) {
  await refresh()
  if (!editing.value && active) workspace.selectProject(project.id)
}
onUnmounted(() => { active = false })
</script>

<template>
  <div>
    <div class="page-header"><div><h1>项目管理</h1><p class="page-description">按项目管理测试空间，集中维护环境、鉴权、数据源与项目资源。</p></div><div class="page-actions"><a-button :disabled="!workspace.selectedProjectId" @click="aiVisible = true"><template #icon><IconRobot /></template>AI 项目资料</a-button><a-button type="primary" :disabled="!projectDefinition" @click="create"><template #icon><IconPlus /></template>新建项目</a-button></div></div>
    <div class="project-exchange"><ExchangeActions :project-id="workspace.selectedProjectId" type="PROJECT" @imported="refresh" /></div>
    <div class="surface project-list">
      <div class="surface-heading"><h2>我的项目 <span class="small muted" style="font-weight: 400; margin-left: 8px">{{ workspace.projects.length }} 个项目</span></h2><a-button type="text" :loading="busy" aria-label="刷新项目" @click="refresh"><IconRefresh /></a-button></div>
      <ErrorNotice class="inline-error" :error="error" retry @retry="refresh" />
      <div v-if="workspace.projects.length" class="table-overflow"><table class="asset-table" aria-label="项目列表"><thead><tr><th>项目名称</th><th>项目说明</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="project in workspace.projects" :key="project.id" :data-project-id="project.id"><td><InlineAssetName :asset="project" :disabled="busy" @open="workspace.selectProject(project.id)" @rename="rename(project, $event)" /><a-tag v-if="project.id === workspace.selectedProjectId" color="arcoblue" size="small" style="margin-left: 12px">当前</a-tag></td><td class="muted" style="max-width: 340px">{{ project.data.description || '未填写说明' }}</td><td class="small muted" style="white-space: nowrap">{{ formatTime(project.updatedAt) }}</td><td><div class="row-actions"><a-button type="text" size="small" @click="workspace.selectProject(project.id)">进入项目</a-button><a-button type="text" size="small" :disabled="busy" :aria-label="`编辑项目 ${project.name}`" @click="edit(project)"><IconEdit /></a-button><a-popconfirm :content="`删除项目「${project.name}」及其全部资产？此操作不可撤销。`" type="warning" ok-text="删除项目" @ok="remove(project)"><a-button type="text" status="danger" size="small" :disabled="busy" :aria-label="`删除项目 ${project.name}`"><IconDelete /></a-button></a-popconfirm></div></td></tr></tbody></table></div>
      <EmptyState v-else title="创建你的第一个测试项目" description="用项目连接需求、用例、接口与执行环境。项目创建后即可添加测试资产。" action="新建项目" @action="create" />
    </div>
    <SourceAnalysisPanel v-if="workspace.currentProject" :key="workspace.currentProject.id" :project="workspace.currentProject" @changed="refresh" />
    <AssetWorkspace v-if="workspace.selectedProjectId" embedded title="项目资源" description="当前项目的共享配置与资料。环境、数据源及模块均保存为独立记录。" :types="['ENVIRONMENT', 'AUTH_CONFIG', 'DATABASE_SOURCE', 'MODULE', 'REQUIREMENT', 'DATASET', 'WEBHOOK']" />
    <AssetEditorDialog v-if="projectDefinition" v-model:visible="dialogVisible" :project-id="workspace.selectedProjectId" :definition="projectDefinition" :asset="editing" @saved="saved" />
    <AiDrawer v-model:visible="aiVisible" :generation="{projectId: workspace.selectedProjectId, type: 'PROJECT', label: '当前项目资料'}" @generated="refresh" />
  </div>
</template>

<style scoped>.project-exchange { margin-bottom: 18px; }</style>
