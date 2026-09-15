<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { Asset, CatalogType } from '../../api/types'
import { assetApi } from '../../api/assets'
import { buildAssetPatch } from '../../core/asset-patch'
import { RequestScope } from '../../core/request-scope'
import CatalogForm from './CatalogForm.vue'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ projectId: string; definition: CatalogType; asset?: Asset; parentId?: string; parents?: { label: string; value: string }[] }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ saved: [asset: Asset] }>()
const form = ref<InstanceType<typeof CatalogForm>>()
const busy = ref(false)
const error = ref<unknown>()
const selectedParent = ref('')
const scope = new RequestScope()
watch(() => [visible.value, props.projectId, props.asset?.id, props.definition.type], () => {
  scope.invalidate()
  busy.value = false
  error.value = undefined
  selectedParent.value = props.parentId ?? ''
})

async function save() {
  const draft = form.value?.read()
  if (!draft || busy.value) return
  const token = scope.begin(`${props.projectId}:${props.asset?.id ?? props.definition.type}`)
  busy.value = true
  error.value = undefined
  try {
    let asset: Asset
    if (props.asset) {
      const patch = buildAssetPatch(form.value?.baseline ?? props.asset, draft, props.definition.fields.map((field) => field.key))
      if (!patch) { visible.value = false; return }
      asset = await assetApi.patch(props.asset.projectId, props.asset.id, patch)
    } else if (props.definition.type === 'PROJECT') {
      asset = await assetApi.createProject(draft.name, draft.data)
    } else {
      asset = await assetApi.create(props.projectId, { type: props.definition.type, ...draft, ...(selectedParent.value ? { parentId: selectedParent.value } : {}) })
    }
    if (!scope.isCurrent(token)) return
    emit('saved', asset)
    Message.success(props.asset ? '修改已保存' : `${props.definition.label}已创建`)
    visible.value = false
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-modal v-model:visible="visible" :title="`${asset ? '编辑' : '新建'}${definition.label}`" role="dialog" aria-modal="true" :aria-label="`${asset ? '编辑' : '新建'}${definition.label}`" :width="700" :mask-closable="!busy" :footer="false" unmount-on-close>
    <a-form v-if="!asset && parents?.length && !parentId" :model="{}" layout="vertical"><a-form-item label="所属位置"><a-select v-model="selectedParent" :options="[{ label: '根节点', value: '' }, ...parents]" /></a-form-item></a-form>
    <CatalogForm ref="form" :definition="definition" :asset="asset" :project-id="projectId" :disabled="busy" />
    <ErrorNotice :error="error" style="margin-bottom: 20px" />
    <div class="inline-actions" style="justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 18px">
      <a-button :disabled="busy" @click="visible = false">取消</a-button>
      <a-button type="primary" :loading="busy" @click="save">{{ asset ? '保存修改' : '创建' }}</a-button>
    </div>
  </a-modal>
</template>
