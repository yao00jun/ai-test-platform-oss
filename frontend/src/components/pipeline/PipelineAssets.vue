<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { assetApi } from '../../api/assets'
import { ApiError } from '../../api/client'
import type { Asset, AssetPatch } from '../../api/types'
import { RequestScope } from '../../core/request-scope'
import AssetTable from '../assets/AssetTable.vue'
import AssetDetailDrawer from '../assets/AssetDetailDrawer.vue'
import AiDrawer from '../ai/AiDrawer.vue'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ projectId: string; assetIds: string[]; revision?: string }>()
const emit = defineEmits<{ changed: [] }>()
const assets = ref<Asset[]>([]), unavailable = ref<string[]>([]), page = ref(1), loading = ref(false), busy = ref(false), error = ref<unknown>()
const detailVisible = ref(false), detailId = ref(''), aiVisible = ref(false), aiTarget = ref<Asset>()
const detail = ref<InstanceType<typeof AssetDetailDrawer>>()
const scope = new RequestScope(), reads = new RequestScope()
watch(() => props.projectId, () => { scope.invalidate(); reads.invalidate(); page.value = 1; assets.value = []; unavailable.value = []; detailVisible.value = false; aiVisible.value = false; busy.value = false; error.value = undefined }, { immediate: true })
watch([() => props.projectId, () => props.assetIds.join(','), () => props.revision, page], () => { void load() }, { immediate: true })
async function load() {
  const token = reads.begin(`${props.projectId}:${page.value}`); loading.value = true; error.value = undefined
  const ids = props.assetIds.slice((page.value - 1) * 20, page.value * 20)
  const results = await Promise.allSettled(ids.map(id => assetApi.get(props.projectId, id, token.signal)))
  if (!reads.isCurrent(token)) return
  assets.value = []; unavailable.value = []
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') assets.value.push(result.value)
    else if (result.reason instanceof ApiError && result.reason.status === 404) unavailable.value.push(ids[index]!)
    else error.value = result.reason
  })
  loading.value = false
}
function open(asset: Asset) { detailId.value = asset.id; detailVisible.value = true }
function refine(asset: Asset) { aiTarget.value = asset; aiVisible.value = true }
function changed() { void load(); emit('changed') }
function applyChanges(updated: Asset[], deletedIds: string[] = []) {
  reads.invalidate(); loading.value = false
  for (const asset of updated) {
    if (asset.projectId !== props.projectId) continue
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = asset
    detail.value?.applyAsset(asset)
    if (aiTarget.value?.id === asset.id) aiTarget.value = asset
  }
  const removed = new Set(deletedIds)
  assets.value = assets.value.filter(asset => !removed.has(asset.id))
  unavailable.value = [...new Set([...unavailable.value, ...deletedIds.filter(id => props.assetIds.includes(id))])]
  if (removed.has(detailId.value)) detailVisible.value = false
  if (aiTarget.value && removed.has(aiTarget.value.id)) aiVisible.value = false
}
function refined(asset: Asset) { applyChanges([asset]); emit('changed') }
async function mutate(asset: Asset, patch?: AssetPatch) {
  if (busy.value) return
  const token = scope.begin(props.projectId); busy.value = true; error.value = undefined
  try {
    if (patch) await assetApi.patch(asset.projectId, asset.id, patch); else await assetApi.remove(asset)
    if (scope.isCurrent(token)) changed()
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => { scope.invalidate(); reads.invalidate() })
defineExpose({ applyChanges })
</script>

<template>
  <section class="pipeline-assets" aria-label="流水线涉及资产">
    <div class="pipeline-assets-heading"><h3>涉及资产 · {{ assetIds.length }}</h3><a-button size="small" :loading="loading" @click="load">刷新资产</a-button></div>
    <p class="small muted">双击名称编辑，打开记录可调整子步骤；每条记录均可单独 AI 调优。</p>
    <ErrorNotice :error="error" retry @retry="load" />
    <AssetTable v-if="assets.length" :assets="assets" compact show-type :busy="busy" @open="open" @rename="(asset, name) => mutate(asset, { baseVersion: asset.version, name })" @confirm="(asset, confirmed) => mutate(asset, { baseVersion: asset.version, confirmed })" @remove="asset => mutate(asset)" @refine="refine" />
    <p v-for="id in unavailable" :key="id" class="small muted">资产 {{ id.slice(0, 12) }} 已删除，历史关联保留。</p>
    <a-pagination v-if="assetIds.length > 20" v-model:current="page" :total="assetIds.length" :page-size="20" />
    <AssetDetailDrawer ref="detail" v-model:visible="detailVisible" :project-id="projectId" :asset-id="detailId" @changed="changed" @removed="changed" @refine="refine" />
    <AiDrawer v-model:visible="aiVisible" :target="aiTarget" @applied="refined" />
  </section>
</template>

<style scoped>
.pipeline-assets { margin-top: 28px; }.pipeline-assets-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }h3 { font-size: 16px; margin: 0; }.arco-pagination { margin-top: 18px; }
</style>
