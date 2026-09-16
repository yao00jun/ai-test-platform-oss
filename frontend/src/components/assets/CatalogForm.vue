<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import type { Asset, AssetDraft, CatalogType } from '../../api/types'
import { compileFields, initialFieldValues } from '../../core/catalog-form'
import AdvancedEditor from '../editors/AdvancedEditor.vue'
import DatasetGridEditor from '../datasets/DatasetGridEditor.vue'
import AppTabs from '../common/AppTabs.vue'
import { compileUiStepDsl } from '../../core/ui-step-dsl'
import GenerationEvidencePanel from '../analysis/GenerationEvidencePanel.vue'
import SourceSnapshotSelect from '../analysis/SourceSnapshotSelect.vue'
import DashboardCardEditor from '../workbench/DashboardCardEditor.vue'

const props = defineProps<{ definition: CatalogType; asset?: Asset; projectId?: string; disabled?: boolean }>()
const name = ref('')
const values = ref<Record<string, unknown>>({})
const errors = ref<Record<string, string>>({})
const formModel = computed(() => ({ ...values.value, name: name.value }))
const formId = useId()
const inputId = (key: string) => `${formId}-${key}`
const baseline = ref<Asset>()
const initialName = ref('')
const initialValues = ref<Record<string, unknown>>({})
const datasetEditor = ref<InstanceType<typeof DatasetGridEditor>>()
const cardEditor = ref<InstanceType<typeof DashboardCardEditor>>()
const editorKey = ref(0)
const uiMode = ref('form'), uiDsl = ref(''), initialUiDsl = ref(''), uiDslError = ref('')
const uiDslDirty = computed(() => props.definition.type === 'UI_STEP' && uiMode.value === 'dsl' && uiDsl.value !== initialUiDsl.value)
const editableDefinition = computed(() => ({ ...props.definition, fields: props.definition.fields.filter(field => field.key !== 'generationEvidence') }))
const hasSource = computed(() => props.definition.fields.some(field => field.key === 'sourceSnapshotId'))
const sourceSelection = computed({ get: () => String(values.value.sourceSnapshotId ?? ''), set: value => { values.value.sourceSnapshotId = value } })
const regularFields = computed(() => editableDefinition.value.fields.filter(field => field.key !== 'sourceSnapshotId' && (props.definition.type !== 'DATASET' || !['columns', 'rows'].includes(field.key)) && (props.definition.type !== 'DASHBOARD' || field.key !== 'cards')))
const editedKeys = computed(() => Object.keys(values.value).filter(key => JSON.stringify(values.value[key]) !== JSON.stringify(initialValues.value[key])))
const dirty = computed(() => name.value !== initialName.value || editedKeys.value.length > 0 || datasetEditor.value?.dirty === true || uiDslDirty.value)
const stale = computed(() => !!baseline.value && props.asset?.id === baseline.value.id && props.asset.version !== baseline.value.version)

function reset(asset = props.asset) {
  editorKey.value++
  baseline.value = asset ? JSON.parse(JSON.stringify(asset)) as Asset : undefined
  name.value = asset?.name ?? ''
  values.value = initialFieldValues(editableDefinition.value, asset?.data)
  initialName.value = name.value
  initialValues.value = { ...values.value }
  errors.value = {}
  uiMode.value = 'form'; uiDsl.value = ''; initialUiDsl.value = ''; uiDslError.value = ''
}
watch(() => [props.asset?.id, props.asset?.version, props.definition.type], (next, previous) => {
  if (!previous || next[0] !== previous[0] || next[2] !== previous[2] || !dirty.value) reset()
}, { immediate: true })

function rebase() {
  if (props.definition.type === 'DATASET' && datasetEditor.value && !datasetEditor.value.flush()) return
  if (props.definition.type === 'DASHBOARD' && cardEditor.value && !cardEditor.value.flush()) return
  if (!flushUiDsl()) return
  const wasDsl = uiMode.value === 'dsl'
  const edited = Object.fromEntries(editedKeys.value.map(key => [key, values.value[key]]))
  const editedName = name.value !== initialName.value ? name.value : undefined
  reset()
  Object.assign(values.value, edited)
  if (editedName !== undefined) name.value = editedName
  if (wasDsl) switchUiMode('dsl')
}
function flushUiDsl() {
  if (props.definition.type !== 'UI_STEP' || uiMode.value !== 'dsl') return true
  try {
    const current = compileFields(editableDefinition.value, values.value, baseline.value?.data)
    const result = compileUiStepDsl(props.definition, uiDsl.value, current.data)
    values.value = initialFieldValues(editableDefinition.value, result)
    uiDslError.value = ''
    return true
  } catch (failure) { uiDslError.value = failure instanceof Error ? failure.message : String(failure); return false }
}
function switchUiMode(next: string) {
  if (next === uiMode.value) return
  if (next === 'form') { if (flushUiDsl()) uiMode.value = next; return }
  const current = compileFields(editableDefinition.value, values.value, baseline.value?.data)
  errors.value = current.errors
  if (Object.keys(current.errors).length) return
  uiDsl.value = JSON.stringify(Object.fromEntries(regularFields.value.map(field => [field.key, current.data[field.key]])), null, 2)
  initialUiDsl.value = uiDsl.value; uiDslError.value = ''; uiMode.value = next
}
function compareValue(key: string, value: unknown) {
  return props.definition.fields.find(field => field.key === key)?.kind === 'password' ? '凭证内容已隐藏' : typeof value === 'string' ? value : JSON.stringify(value ?? null)
}
function read(): AssetDraft | null {
  if (props.definition.type === 'DATASET' && datasetEditor.value && !datasetEditor.value.flush()) return null
  if (props.definition.type === 'DASHBOARD' && cardEditor.value && !cardEditor.value.flush()) return null
  if (!flushUiDsl()) return null
  const result = compileFields(editableDefinition.value, values.value, baseline.value?.data)
  errors.value = result.errors
  if (!name.value.trim()) errors.value.name = '请填写名称'
  if (name.value.trim().length > 255) errors.value.name = '名称不能超过 255 个字符'
  return Object.keys(errors.value).length ? null : { name: name.value.trim(), data: result.data }
}
defineExpose({ read, baseline, dirty, reset })
</script>

<template>
  <GenerationEvidencePanel v-if="asset" :asset="asset" />
  <a-alert v-if="stale" type="warning" style="margin-bottom: 18px" role="alert">
    <p>记录已更新到 v{{ asset?.version }}。你的未保存修改仍基于 v{{ baseline?.version }}，请核对后选择如何继续。</p>
    <div v-if="name !== initialName" class="draft-comparison"><strong>名称</strong><p>最新内容：{{ asset?.name }}</p><p>我的修改：{{ name }}</p></div>
    <div v-for="key in editedKeys" :key="key" class="draft-comparison"><strong>{{ definition.fields.find(field => field.key === key)?.label ?? key }}</strong><p>最新内容：{{ compareValue(key, asset?.data[key]) }}</p><p>我的修改：{{ compareValue(key, values[key]) }}</p></div>
    <div v-if="uiDslDirty" class="draft-comparison"><strong>待应用的步骤 DSL</strong><pre class="json-view">{{ uiDsl }}</pre></div>
    <div class="inline-actions"><a-button size="small" :disabled="disabled" @click="rebase">保留我的修改并以最新版本继续</a-button><a-popconfirm content="放弃尚未保存的修改并载入最新内容？" @ok="reset()"><a-button size="small" :disabled="disabled">放弃草稿并载入最新内容</a-button></a-popconfirm></div>
  </a-alert>
  <SourceSnapshotSelect v-if="hasSource && (projectId || asset?.projectId)" v-model="sourceSelection" :project-id="projectId || asset!.projectId" :disabled="disabled" label="源码快照（人工绑定）" style="margin-bottom: 20px" />
  <a-form :model="formModel" layout="vertical" :disabled="disabled" @submit.prevent>
    <a-form-item label="名称" field="name" label-component="label" :label-attrs="{ for: inputId('name') }" required :validate-status="errors.name ? 'error' : undefined" :help="errors.name">
      <a-input v-model="name" :input-attrs="{ id: inputId('name'), 'aria-label': '名称' }" :max-length="255" placeholder="为这条记录起一个清晰的名称" allow-clear />
    </a-form-item>
    <AppTabs v-if="definition.type === 'UI_STEP'" :model-value="uiMode" :items="[{ value: 'form', label: '表单编辑' }, { value: 'dsl', label: '步骤 DSL' }]" label="UI 步骤编辑方式" style="padding: 0; margin-bottom: 18px" @update:model-value="switchUiMode" />
    <div v-if="definition.type === 'UI_STEP' && uiMode === 'dsl'" class="ui-dsl-editor"><p class="small muted">修改当前步骤的配置。未填写的字段保留现值；清除文本请填写空字符串，清除附件请填写空数组。</p><AdvancedEditor v-model="uiDsl" language="json" label="UI 步骤 DSL" /><a-alert v-if="uiDslError" type="error" role="alert" style="margin-top: 12px">{{ uiDslError }}</a-alert></div>
    <div v-else class="field-grid">
      <DatasetGridEditor v-if="definition.type === 'DATASET'" :key="editorKey" ref="datasetEditor" :columns="String(values.columns ?? '[]')" :rows="String(values.rows ?? '[]')" :disabled="disabled" @change="Object.assign(values, $event)" />
      <DashboardCardEditor v-if="definition.type === 'DASHBOARD'" :key="editorKey" ref="cardEditor" :model-value="String(values.cards ?? '[]')" :disabled="disabled" @update:model-value="values.cards = $event" />
      <a-form-item v-for="field in regularFields" :key="field.key" :field="field.key" :label="field.label" label-component="label" :label-attrs="{ for: inputId(field.key) }" :required="field.required" :class="{ 'field-wide': ['textarea', 'json', 'sql', 'code'].includes(field.kind) }" :validate-status="errors[field.key] ? 'error' : undefined" :help="errors[field.key]">
        <AdvancedEditor v-if="field.kind === 'json' || field.kind === 'sql' || field.kind === 'code'" :model-value="String(values[field.key] ?? '')" :language="field.kind" :label="field.label" :input-id="inputId(field.key)" @update:model-value="values[field.key] = $event" />
        <a-textarea v-else-if="field.kind === 'textarea'" :model-value="String(values[field.key] ?? '')" :textarea-attrs="{ id: inputId(field.key), 'aria-label': field.label }" :auto-size="{ minRows: 3, maxRows: 10 }" :placeholder="`请输入${field.label}`" @update:model-value="values[field.key] = $event" />
        <a-switch v-else-if="field.kind === 'boolean'" :model-value="values[field.key] === true" :aria-label="field.label" @update:model-value="values[field.key] = $event" />
        <a-input-number v-else-if="field.kind === 'number'" :model-value="values[field.key] === '' ? undefined : Number(values[field.key])" :input-attrs="{ id: inputId(field.key), 'aria-label': field.label }" style="width: 100%" :placeholder="`请输入${field.label}`" @update:model-value="values[field.key] = $event" />
        <a-select v-else-if="field.kind === 'select'" :model-value="String(values[field.key] ?? '')" :options="(field.options ?? []).map((option) => typeof option === 'string' ? { label: option, value: option } : option)" :placeholder="`请选择${field.label}`" @update:model-value="values[field.key] = $event" />
        <a-input-password v-else-if="field.kind === 'password'" :model-value="String(values[field.key] ?? '')" :input-attrs="{ id: inputId(field.key), 'aria-label': field.label, autocomplete: 'new-password' }" :placeholder="asset ? '留空保留现有值' : `请输入${field.label}`" @update:model-value="values[field.key] = $event" />
        <a-input v-else :model-value="String(values[field.key] ?? '')" :input-attrs="{ id: inputId(field.key), 'aria-label': field.label }" :placeholder="`请输入${field.label}`" @update:model-value="values[field.key] = $event" />
      </a-form-item>
    </div>
  </a-form>
</template>

<style scoped>.draft-comparison { margin-block: 10px; overflow-wrap: anywhere; }.draft-comparison p { margin: 4px 0; white-space: pre-wrap; max-height: 160px; overflow: auto; }</style>
