<script setup lang="ts">
import { ref, watch } from 'vue'
import { IconDelete, IconDragDotVertical, IconPlus } from '@arco-design/web-vue/es/icon'
import { cardDefinitions, cardSizes, createDashboardCard, moveDashboardCard, parseDashboardCards, updateDashboardCard, type DashboardCard, type DashboardCardType } from '../../core/dashboard-cards'
import AdvancedEditor from '../editors/AdvancedEditor.vue'
import AppTabs from '../common/AppTabs.vue'

const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const cards = ref<DashboardCard[]>([]), text = ref(''), error = ref(''), mode = ref('cards')
const newType = ref<DashboardCardType>('quality'), draggingId = ref('')
function load(value: string) {
  text.value = value
  try { cards.value = parseDashboardCards(value); error.value = '' }
  catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure); mode.value = 'json' }
}
watch(() => props.modelValue, value => { if (value !== text.value) load(value) }, { immediate: true })
function publish(value: DashboardCard[]) {
  if (props.disabled) return
  cards.value = value; text.value = JSON.stringify(value, null, 2); emit('update:modelValue', text.value)
}
function update(id: string, patch: Partial<Omit<DashboardCard, 'id' | 'schemaVersion'>>) { publish(updateDashboardCard(cards.value, id, patch)) }
function changeType(card: DashboardCard, type: DashboardCardType) {
  const replacement = { ...createDashboardCard(type, card.id), title: card.title, size: card.size, visible: card.visible }
  publish(cards.value.map(value => value.id === card.id ? replacement : value))
}
function move(id: string, to: number) { publish(moveDashboardCard(cards.value, id, to)) }
function drop(to: number) { if (draggingId.value) move(draggingId.value, to); draggingId.value = '' }
function fieldsChanged(card: DashboardCard, key: string, checked: boolean) {
  update(card.id, { fields: checked ? [...card.fields, key] : card.fields.filter(field => field !== key) })
}
function flush(): boolean {
  try { const validated = parseDashboardCards(mode.value === 'json' ? text.value : cards.value); cards.value = validated; error.value = ''; return true }
  catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure); return false }
}
function switchMode(next: string) { if (flush()) mode.value = next }
function editJson(value: string) { if (!props.disabled) { text.value = value; emit('update:modelValue', value) } }
defineExpose({ flush })
</script>

<template>
  <section class="card-editor field-wide" role="region" aria-label="看板卡片编辑">
    <div class="card-editor-heading"><strong>看板卡片</strong><span class="small muted">{{ cards.length }} / 50 张</span></div>
    <AppTabs :model-value="mode" :items="[{ value: 'cards', label: '卡片编辑' }, { value: 'json', label: 'JSON 编辑' }]" label="看板编辑方式" @update:model-value="switchMode" />
    <a-alert v-if="error" type="error" role="alert" class="card-error">{{ error }}</a-alert>
    <div v-if="mode === 'cards'" class="card-editor-body">
      <p class="small muted">拖动手柄或使用上下方向键调整顺序。保存修改后，当前项目的看板即按此布局显示。</p>
      <article v-for="(card, index) in cards" :key="card.id" :data-card-editor-id="card.id" class="card-config" :class="{ dragging: draggingId === card.id }" @dragover.prevent @drop.prevent="drop(index)">
        <div class="card-config-heading"><button type="button" class="drag-handle" :draggable="!disabled" :disabled="disabled" :aria-label="`调整 ${card.title} 顺序，按上或下方向键移动`" @dragstart="draggingId = card.id" @dragend="draggingId = ''" @keydown.up.prevent="move(card.id, index - 1)" @keydown.down.prevent="move(card.id, index + 1)"><IconDragDotVertical /></button><span class="small muted">卡片 {{ index + 1 }}</span><label class="visibility"><input type="checkbox" :checked="card.visible" :disabled="disabled" @change="update(card.id, { visible: ($event.target as HTMLInputElement).checked })" />显示此卡片</label><a-popconfirm :content="`删除卡片「${card.title}」？保存布局后生效。`" @ok="publish(cards.filter(value => value.id !== card.id))"><a-button type="text" status="danger" :disabled="disabled" :aria-label="`删除卡片 ${card.title}`"><IconDelete /></a-button></a-popconfirm></div>
        <div class="card-config-fields">
          <label>卡片标题<input :value="card.title" :disabled="disabled" maxlength="120" @input="update(card.id, { title: ($event.target as HTMLInputElement).value })" /></label>
          <label>卡片类型<select :value="card.type" :disabled="disabled" @change="changeType(card, ($event.target as HTMLSelectElement).value as DashboardCardType)"><option v-for="(definition, type) in cardDefinitions" :key="type" :value="type">{{ definition.label }}</option></select></label>
          <label>卡片尺寸<select :value="card.size" :disabled="disabled" @change="update(card.id, { size: ($event.target as HTMLSelectElement).value as DashboardCard['size'] })"><option v-for="size in cardSizes" :key="size.value" :value="size.value">{{ size.label }}</option></select></label>
          <label v-if="card.type === 'metric'">指标<select :value="card.metric" :disabled="disabled" @change="update(card.id, { metric: ($event.target as HTMLSelectElement).value as DashboardCard['metric'] })"><option value="cases">功能用例数</option><option value="openBugs">待处理缺陷数</option></select></label>
          <label v-if="card.type === 'bugs' || card.type === 'runs'">最多记录数<input type="number" :value="card.limit" :disabled="disabled" min="1" max="10" @input="update(card.id, { limit: Number(($event.target as HTMLInputElement).value) })" /></label>
        </div>
        <fieldset class="card-field-options" :disabled="disabled"><legend>显示字段（按勾选顺序排列）</legend><label v-for="field in cardDefinitions[card.type].fields" :key="field.key"><input type="checkbox" :checked="card.fields.includes(field.key)" @change="fieldsChanged(card, field.key, ($event.target as HTMLInputElement).checked)" />{{ field.label }}</label></fieldset>
      </article>
      <div class="card-add"><label>添加卡片类型<select v-model="newType" :disabled="disabled"><option v-for="(definition, type) in cardDefinitions" :key="type" :value="type">{{ definition.label }}</option></select></label><a-button :disabled="disabled || cards.length >= 50" @click="publish([...cards, createDashboardCard(newType)])"><template #icon><IconPlus /></template>添加卡片</a-button></div>
    </div>
    <div v-else class="card-editor-body"><p class="small muted">每张卡片保留唯一 ID；显示数值由系统查询。支持单项指标、运行质量、资产、缺陷、运行和模型效能。</p><AdvancedEditor :model-value="text" language="json" label="卡片配置 JSON" @update:model-value="editJson" /></div>
  </section>
</template>

<style scoped>
.card-editor { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; min-width: 0; margin-bottom: 16px; }.card-editor-heading { padding: 18px; display: flex; justify-content: space-between; }.card-editor-body { padding: 0 18px 18px; }.card-error { margin: 12px 18px; width: auto; }.card-config { border: 1px solid var(--border); border-radius: 8px; padding: 12px; margin: 14px 0; background: var(--color-bg-2, white); }.card-config.dragging { opacity: .5; }.card-config-heading { display: flex; align-items: center; gap: 8px; }.visibility { margin-left: auto; }.card-config-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 12px 0; }.card-editor label { font-size: 12px; color: var(--muted); }.card-config-fields label, .card-add > label { display: grid; gap: 6px; }.card-editor input:not([type=checkbox]), .card-editor select { min-width: 0; box-sizing: border-box; width: 100%; border: 1px solid var(--border); border-radius: 5px; padding: 8px; color: var(--text); background: var(--color-bg-2, white); font: inherit; }.card-field-options { display: flex; gap: 8px 14px; flex-wrap: wrap; border: 0; padding: 6px 0 0; margin: 0; }.card-field-options legend { font-size: 12px; color: var(--muted); margin-bottom: 6px; }.card-field-options label, .visibility { display: inline-flex; align-items: center; gap: 5px; }.card-add { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }.card-add > label { flex: 1; min-width: 140px; }
@media (max-width: 600px) { .card-config-fields { grid-template-columns: 1fr; }.card-editor-body { padding-inline: 10px; } }
</style>
