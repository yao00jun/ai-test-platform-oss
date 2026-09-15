<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { IconArrowDown, IconArrowUp, IconDelete, IconDragDotVertical, IconPlus } from '@arco-design/web-vue/es/icon'
import { applyDatasetEdit, cellEditor, readDataset, type CellEditor, type CellKind, type Dataset, type DatasetEdit } from '../../core/dataset-grid'
import AdvancedEditor from '../editors/AdvancedEditor.vue'
import AppTabs from '../common/AppTabs.vue'

const props = defineProps<{ columns: string; rows: string; disabled?: boolean }>()
const emit = defineEmits<{ change: [value: { columns: string; rows: string }] }>()
const id = useId()
const tab = ref('table')
const columnsText = ref(props.columns)
const rowsText = ref(props.rows)
const page = ref(1)
const pageSize = 25
const error = ref('')
const draft = ref<CellEditor>()
const initialCell = ref<CellEditor>()
const newColumn = ref('')
const selectedColumn = ref('')
const renamedColumn = ref('')
const draggingRow = ref<number>()
const draggingColumn = ref<string>()
const kinds: { value: CellKind; label: string }[] = [
  { value: 'string', label: '文本' }, { value: 'number', label: '数字' }, { value: 'boolean', label: '布尔值' },
  { value: 'json', label: 'JSON' }, { value: 'null', label: '空值 null' }, { value: 'missing', label: '未设置' },
]
watch([() => props.columns, () => props.rows], ([columns, rows]) => { columnsText.value = columns; rowsText.value = rows })
const parsed = computed(() => {
  try { return { data: readDataset(columnsText.value, rowsText.value), error: '' } }
  catch (failure) { return { data: undefined, error: failure instanceof Error ? failure.message : String(failure) } }
})
const dirty = computed(() => !!draft.value && (draft.value.kind !== initialCell.value?.kind || draft.value.text !== initialCell.value?.text))
const pageRows = computed(() => parsed.value.data?.rows.slice((page.value - 1) * pageSize, page.value * pageSize) ?? [])
const firstRow = computed(() => (page.value - 1) * pageSize)
watch(() => parsed.value.data, dataset => {
  if (!dataset) return
  page.value = Math.min(page.value, Math.max(1, Math.ceil(dataset.rows.length / pageSize)))
  if (!dataset.columns.includes(selectedColumn.value)) selectedColumn.value = dataset.columns[0] ?? ''
}, { immediate: true })
watch(selectedColumn, column => { renamedColumn.value = column }, { immediate: true })

function current(): Dataset {
  const dataset = parsed.value.data
  if (!dataset) throw new Error(parsed.value.error)
  return dirty.value && draft.value ? applyDatasetEdit(dataset, { type: 'cell', ...draft.value }) : dataset
}
function publish(dataset: Dataset) {
  columnsText.value = JSON.stringify(dataset.columns, null, 2)
  rowsText.value = JSON.stringify(dataset.rows, null, 2)
  emit('change', { columns: columnsText.value, rows: rowsText.value })
}
function discardCell() { draft.value = undefined; initialCell.value = undefined; error.value = '' }
function attempt(operation: () => void): boolean {
  if (props.disabled) return false
  try { operation(); error.value = ''; return true }
  catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure); return false }
}
function flush(): boolean {
  return attempt(() => {
    const dataset = current()
    if (dirty.value) publish(dataset)
    discardCell()
  })
}
function editCell(row: number, column: string) {
  if (!flush()) return
  const dataset = parsed.value.data
  if (!dataset) return
  draft.value = cellEditor(dataset, row, column)
  initialCell.value = { ...draft.value }
}
function mutate(edit: DatasetEdit) {
  return attempt(() => {
    // Resolve a pending cell and the structural edit against one dataset, before emitting either.
    const updated = applyDatasetEdit(current(), edit)
    publish(updated)
    discardCell()
    if (edit.type === 'add-row') page.value = Math.ceil(updated.rows.length / pageSize)
    if (edit.type === 'add-column') { selectedColumn.value = edit.name; newColumn.value = '' }
    if (edit.type === 'rename-column') selectedColumn.value = edit.name
  })
}
function switchTab(next: string) {
  if (next === tab.value) return
  if (!flush()) return
  tab.value = next
}
function updateJson(field: 'columns' | 'rows', text: string) {
  if (props.disabled) return
  if (field === 'columns') columnsText.value = text
  else rowsText.value = text
  emit('change', { columns: columnsText.value, rows: rowsText.value })
}
function displayCell(row: Record<string, unknown>, column: string) {
  if (!Object.hasOwn(row, column)) return '未设置'
  if (row[column] === null) return 'null'
  const value = typeof row[column] === 'string' ? row[column] as string : JSON.stringify(row[column])
  return value === '' ? '空文本' : value.length > 140 ? `${value.slice(0, 140)}…` : value
}
function moveColumn(direction: -1 | 1) {
  const from = parsed.value.data?.columns.indexOf(selectedColumn.value) ?? -1
  if (from >= 0) mutate({ type: 'move-column', column: selectedColumn.value, to: from + direction })
}
function rowDrop(to: number) {
  if (draggingRow.value !== undefined) mutate({ type: 'move-row', row: draggingRow.value, to })
  draggingRow.value = undefined
}
function columnDrop(to: number) {
  if (draggingColumn.value !== undefined) mutate({ type: 'move-column', column: draggingColumn.value, to })
  draggingColumn.value = undefined
}
defineExpose({ flush, dirty })
</script>

<template>
  <section class="dataset-editor field-wide" role="region" aria-label="数据集表格">
    <div class="dataset-heading"><strong>数据集</strong><span class="small muted">{{ parsed.data?.rows.length ?? '—' }} 行 · {{ parsed.data?.columns.length ?? '—' }} 列</span></div>
    <AppTabs :model-value="tab" :items="[{ value: 'table', label: '表格编辑' }, { value: 'json', label: 'JSON 编辑' }]" label="数据集编辑方式" @update:model-value="switchTab" />
    <a-alert v-if="error || (tab === 'table' && parsed.error)" type="error" role="alert" class="dataset-error">{{ error || parsed.error }}</a-alert>
    <div v-if="tab === 'table'" role="tabpanel" aria-label="表格编辑">
      <fieldset :disabled="disabled" class="dataset-controls">
        <div class="column-actions">
          <label :for="`${id}-new-column`">新列名称<input :id="`${id}-new-column`" v-model="newColumn" autocomplete="off" placeholder="例如 orderNo" /></label>
          <a-button size="small" :disabled="disabled || !parsed.data" @click="mutate({ type: 'add-column', name: newColumn })"><template #icon><IconPlus /></template>添加列</a-button>
          <a-button size="small" :disabled="disabled || !parsed.data" @click="mutate({ type: 'add-row' })"><template #icon><IconPlus /></template>添加数据行</a-button>
        </div>
        <div v-if="parsed.data?.columns.length" class="column-actions column-management">
          <label :for="`${id}-column`">管理数据列<select :id="`${id}-column`" v-model="selectedColumn" aria-label="管理数据列"><option v-for="column in parsed.data.columns" :key="column" :value="column">{{ column }}</option></select></label>
          <label :for="`${id}-rename-column`">修改列名称<input :id="`${id}-rename-column`" v-model="renamedColumn" autocomplete="off" /></label>
          <a-button size="small" :disabled="disabled" @click="mutate({ type: 'rename-column', column: selectedColumn, name: renamedColumn })">重命名列</a-button>
          <a-button size="small" :disabled="disabled || parsed.data.columns.indexOf(selectedColumn) <= 0" @click="moveColumn(-1)">前移列</a-button>
          <a-button size="small" :disabled="disabled || parsed.data.columns.indexOf(selectedColumn) >= parsed.data.columns.length - 1" @click="moveColumn(1)">后移列</a-button>
          <a-popconfirm :content="`删除列 ${selectedColumn} 及这一列的所有数据？保存后才会生效。`" @ok="mutate({ type: 'remove-column', column: selectedColumn })"><a-button size="small" status="danger" :disabled="disabled">删除列</a-button></a-popconfirm>
        </div>
      </fieldset>
      <div v-if="draft" class="cell-editor">
        <div class="dataset-heading"><strong>第 {{ draft.row + 1 }} 行 · {{ draft.column }}</strong><span v-if="dirty" class="small muted">待应用的修改</span></div>
        <div class="cell-fields">
          <label :for="`${id}-kind`">单元格类型<select :id="`${id}-kind`" v-model="draft.kind" aria-label="单元格类型" :disabled="disabled"><option v-for="kind in kinds" :key="kind.value" :value="kind.value">{{ kind.label }}</option></select></label>
          <label v-if="draft.kind !== 'null' && draft.kind !== 'missing'" :for="`${id}-cell`">单元格内容<textarea :id="`${id}-cell`" v-model="draft.text" :disabled="disabled" :rows="draft.kind === 'json' ? 5 : 2" spellcheck="false" /></label>
          <p v-else class="small muted">{{ draft.kind === 'null' ? '该单元格将保存为 JSON null。' : '该行不再包含此字段；不会替换为空文本或 null。' }}</p>
        </div>
        <div class="inline-actions"><a-button size="small" type="primary" :disabled="disabled" @click="flush">应用单元格</a-button><a-button size="small" :disabled="disabled" @click="discardCell">取消单元格修改</a-button></div>
      </div>
      <div class="dataset-scroll" tabindex="0" aria-label="数据行与列">
        <table v-if="parsed.data" class="dataset-table">
          <thead><tr><th scope="col" class="row-number">行</th><th v-for="(column, columnIndex) in parsed.data.columns" :key="column" scope="col" @dragover.prevent @drop.prevent="columnDrop(columnIndex)"><button type="button" class="column-grip" :draggable="!disabled" :disabled="disabled" :aria-label="`拖动列 ${column}`" @dragstart="draggingColumn = column" @dragend="draggingColumn = undefined"><IconDragDotVertical /></button>{{ column }}</th><th scope="col">行操作</th></tr></thead>
          <tbody>
            <tr v-for="(row, offset) in pageRows" :key="firstRow + offset" @dragover.prevent @drop.prevent="rowDrop(firstRow + offset)">
              <th scope="row"><button type="button" class="row-grip" :draggable="!disabled" :disabled="disabled" :aria-label="`拖动第 ${firstRow + offset + 1} 行`" @dragstart="draggingRow = firstRow + offset" @dragend="draggingRow = undefined"><IconDragDotVertical />{{ firstRow + offset + 1 }}</button></th>
              <td v-for="column in parsed.data.columns" :key="column"><button type="button" class="dataset-cell" :class="{ 'empty-cell': !Object.hasOwn(row, column) || row[column] === null }" :disabled="disabled" :aria-label="`编辑第 ${firstRow + offset + 1} 行 ${column}`" @click="editCell(firstRow + offset, column)">{{ displayCell(row, column) }}</button></td>
              <td><div class="row-actions"><a-button size="mini" :aria-label="`上移第 ${firstRow + offset + 1} 行`" :disabled="disabled || firstRow + offset === 0" @click="mutate({ type: 'move-row', row: firstRow + offset, to: firstRow + offset - 1 })"><IconArrowUp /></a-button><a-button size="mini" :aria-label="`下移第 ${firstRow + offset + 1} 行`" :disabled="disabled || firstRow + offset === parsed.data.rows.length - 1" @click="mutate({ type: 'move-row', row: firstRow + offset, to: firstRow + offset + 1 })"><IconArrowDown /></a-button><a-popconfirm :content="`删除第 ${firstRow + offset + 1} 行？保存后才会生效。`" @ok="mutate({ type: 'remove-row', row: firstRow + offset })"><a-button size="mini" status="danger" :aria-label="`删除第 ${firstRow + offset + 1} 行`" :disabled="disabled"><IconDelete /></a-button></a-popconfirm></div></td>
            </tr>
            <tr v-if="!pageRows.length"><td :colspan="parsed.data.columns.length + 2" class="dataset-empty">添加列和数据行，或切换到 JSON 编辑粘贴已有数据。</td></tr>
          </tbody>
        </table>
      </div>
      <div class="dataset-pagination"><span class="small muted">点击单元格编辑；拖动手柄可调整行列顺序。</span><a-pagination v-if="(parsed.data?.rows.length ?? 0) > pageSize" v-model:current="page" :total="parsed.data?.rows.length ?? 0" :page-size="pageSize" simple /></div>
    </div>
    <div v-else class="dataset-json" role="tabpanel" aria-label="JSON 编辑">
      <p class="small muted">列名是字符串数组，数据行是对象数组。文本、数字、布尔值、null 与未设置字段会分别保留。</p>
      <label>列名</label><AdvancedEditor :model-value="columnsText" language="json" label="列名" @update:model-value="updateJson('columns', $event)" />
      <label>数据行</label><AdvancedEditor :model-value="rowsText" language="json" label="数据行" @update:model-value="updateJson('rows', $event)" />
      <p v-if="parsed.error" role="alert" class="editor-error">{{ parsed.error }}</p>
    </div>
  </section>
</template>

<style scoped>
.dataset-editor { min-width: 0; margin-bottom: 20px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.dataset-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 18px 0; }
.dataset-controls { border: 0; padding: 16px 18px; margin: 0; min-width: 0; }
.column-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: end; }
.column-management { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--border); }
.dataset-editor label { display: grid; gap: 6px; font-size: 12px; color: var(--muted, #66768b); }
.dataset-editor input, .dataset-editor select, .dataset-editor textarea { box-sizing: border-box; width: 100%; min-width: 0; font: inherit; color: var(--text, #17283f); background: var(--color-bg-2, #fff); border: 1px solid var(--border); border-radius: 5px; padding: 7px 9px; }
.column-actions label { flex: 0 1 150px; min-width: 100px; }
.dataset-error { margin: 12px 18px; width: auto; }
.cell-editor { padding: 0 18px 16px; margin: 0 12px 16px; background: var(--color-fill-1, #f7f9fc); border-radius: 8px; }
.cell-editor .dataset-heading { padding-left: 0; padding-right: 0; overflow-wrap: anywhere; }
.cell-fields { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 12px; padding: 12px 0; align-items: start; }
.dataset-scroll { width: 100%; max-height: 530px; overflow: auto; border-block: 1px solid var(--border); }
.dataset-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 12px; text-align: left; }
.dataset-table th, .dataset-table td { border-bottom: 1px solid var(--border); border-right: 1px solid var(--border); padding: 0; }
.dataset-table thead th { position: sticky; top: 0; z-index: 1; background: var(--color-fill-2, #f1f5fa); padding: 8px; white-space: nowrap; }
.dataset-table tbody tr:last-child > * { border-bottom: 0; }
.dataset-table th:last-child, .dataset-table td:last-child { border-right: 0; }
.dataset-cell { display: block; border: 0; background: transparent; font: inherit; color: inherit; text-align: left; width: 100%; min-width: 95px; max-width: 230px; min-height: 38px; padding: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: text; }
.dataset-cell:hover { background: var(--color-primary-light-1, #edf4ff); }
.dataset-cell:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.empty-cell { color: var(--muted, #8995a5); font-style: italic; }
.row-grip, .column-grip { border: 0; background: transparent; color: var(--muted, #8995a5); cursor: grab; }
.row-grip { display: flex; align-items: center; gap: 4px; padding: 8px; font-size: 11px; }
.column-grip { padding: 0 3px 0 0; }
.row-actions { display: flex; gap: 4px; padding: 5px; }
.dataset-empty { padding: 24px !important; text-align: center; color: var(--muted, #8995a5); }
.dataset-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 18px; }
.dataset-json { display: grid; gap: 8px; padding: 14px 18px 18px; }
@media (max-width: 600px) { .cell-fields { grid-template-columns: 1fr; } .dataset-pagination { align-items: start; flex-direction: column; } }
</style>
