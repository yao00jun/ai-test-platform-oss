<script setup lang="ts">
import { IconDelete, IconDragDotVertical, IconEdit, IconRobot } from '@arco-design/web-vue/es/icon'
import type { Asset } from '../../api/types'
import { useAssetReorder } from '../../composables/useAssetReorder'
import InlineAssetName from './InlineAssetName.vue'
import SafeRichText from '../common/SafeRichText.vue'

const props = defineProps<{ assets: Asset[]; busy?: boolean; sortable?: boolean }>()
const emit = defineEmits<{ open: [asset: Asset]; rename: [asset: Asset, name: string]; confirm: [asset: Asset, confirmed: boolean]; refine: [asset: Asset]; remove: [asset: Asset]; reorder: [assets: Asset[]] }>()
const { draggingId, move, dragStart, drop } = useAssetReorder(() => props.assets, () => !!props.sortable && !props.busy, assets => emit('reorder', assets))
const actionLabels: Record<string, string> = { navigate: '打开页面', click: '点击', dblclick: '双击', fill: '填写', press: '按键', select: '选择', selectOption: '选择选项', check: '勾选', uncheck: '取消勾选', hover: '悬停', dragAndDrop: '拖放', upload: '上传', wait: '等待', waitFor: '等待元素', assertText: '校验文本', assertVisible: '校验可见', assertHidden: '校验隐藏', assertUrl: '校验地址', assertValue: '校验输入值', assertCount: '校验数量', screenshot: '截图', extract: '提取变量', popup: '打开新页面', download: '下载', closePage: '关闭页面' }
function kind(asset: Asset) {
  if (asset.type === 'UI_STEP') return actionLabels[String(asset.data.action)] ?? String(asset.data.action)
  if (asset.type === 'SQL_VALIDATION') return 'SQL 校验'
  if (asset.type === 'SCENARIO_STEP') return String(asset.data.stepType)
  return '功能步骤'
}
function fields(asset: Asset) {
  const entries = asset.type === 'UI_STEP' ? [['selector', '定位器'], ['url', 'URL'], ['value', '输入值'], ['expected', '预期值'], ['frame', 'Frame'], ['pageAlias', '页面别名'], ['saveAs', '保存为'], ['targetSelector', '拖放目标'], ['timeoutMs', '超时（毫秒）']]
    : [['targetId', '引用资产'], ['waitMs', '等待（毫秒）']]
  return entries.filter(([key]) => asset.data[key!] !== '' && asset.data[key!] !== undefined && asset.data[key!] !== null && (key !== 'waitMs' || asset.data.stepType === 'WAIT')).map(([key, label]) => ({ key: key!, label: label!, value: String(asset.data[key!]) }))
}
</script>

<template>
  <section class="step-cards" role="region" aria-label="步骤编排">
    <article v-for="(asset, index) in assets" :key="asset.id" class="step-card" :data-step-id="asset.id" :class="{ dragging: draggingId === asset.id }" :draggable="sortable && !busy" @dragstart="dragStart($event, asset)" @dragover.prevent @drop.prevent="drop(index)" @dragend="draggingId = ''">
      <div class="step-heading"><span class="step-number">{{ index + 1 }}</span><button type="button" class="drag-handle" :disabled="!sortable || busy" :aria-label="`调整 ${asset.name} 顺序，按上或下方向键移动`" @keydown.up.prevent="move(asset.id, index - 1)" @keydown.down.prevent="move(asset.id, index + 1)"><IconDragDotVertical /></button><InlineAssetName :asset="asset" :disabled="busy" @open="emit('open', asset)" @rename="emit('rename', asset, $event)" /><a-tag size="small" color="arcoblue">{{ kind(asset) }}</a-tag><span class="small mono muted">v{{ asset.version }}</span></div>
      <div v-if="asset.type === 'FUNCTIONAL_STEP'" class="functional-step-body"><div><h5>测试步骤</h5><SafeRichText :value="String(asset.data.step ?? '')" /></div><div><h5>预期结果</h5><SafeRichText :value="String(asset.data.expected || '未填写预期结果')" /></div></div>
      <template v-else-if="asset.type === 'SQL_VALIDATION'"><pre class="step-sql">{{ asset.data.sql }}</pre><p class="small muted">{{ asset.data.allowWrite ? '受保护写入' : '只读校验' }} · {{ asset.data.dryRun ? '执行后回滚' : '按环境策略提交' }}</p></template>
      <dl v-else class="step-fields"><template v-for="field in fields(asset)" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
      <div class="step-actions"><a-checkbox :model-value="asset.confirmed" :disabled="busy" :aria-label="`确认 ${asset.name}`" @change="emit('confirm', asset, $event === true)"><span class="small muted">{{ asset.confirmed ? '已确认' : '待确认' }}</span></a-checkbox><div class="inline-actions"><a-button type="text" size="small" :disabled="busy" :aria-label="`编辑 ${asset.name}`" @click="emit('open', asset)"><template #icon><IconEdit /></template>编辑</a-button><a-button type="text" size="small" :disabled="busy" :aria-label="`AI 优化 ${asset.name}`" @click="emit('refine', asset)"><template #icon><IconRobot /></template>局部 AI 调优</a-button><a-popconfirm :content="`删除「${asset.name}」及其子项？此操作不可撤销。`" @ok="emit('remove', asset)"><a-button type="text" size="small" status="danger" :disabled="busy" :aria-label="`删除 ${asset.name}`"><IconDelete /></a-button></a-popconfirm></div></div>
    </article>
  </section>
</template>

<style scoped>
.step-cards { padding: 16px; display: grid; gap: 16px; background: var(--color-fill-1); border-radius: 10px; }.step-card { padding: 16px; background: var(--color-bg-2, white); border: 1px solid var(--border); border-radius: 9px; min-width: 0; }.step-card.dragging { opacity: .5; }.step-heading { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }.step-heading :deep(.asset-name) { flex: 1; min-width: 100px; }.step-number { display: grid; place-items: center; width: 25px; height: 25px; border-radius: 50%; background: #eeedff; color: var(--primary); font-size: 12px; }.functional-step-body { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; font-size: 13px; margin: 14px 0; }.functional-step-body > div { min-width: 0; }.functional-step-body h5 { margin: 0 0 6px; color: var(--muted); font-size: 12px; }.step-fields { display: grid; grid-template-columns: 95px minmax(0, 1fr); gap: 8px 12px; margin: 15px 0; font-size: 12px; }.step-fields dt { color: var(--muted); }.step-fields dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono, monospace); }.step-sql { padding: 12px; background: #f6f8fc; border-radius: 6px; font-size: 12px; overflow: auto; max-height: 250px; }.step-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
@media (max-width: 600px) { .step-cards { padding: 10px; }.step-card { padding: 12px; }.functional-step-body { grid-template-columns: 1fr; }.step-fields { grid-template-columns: 1fr; gap: 5px; }.step-fields dd { margin-bottom: 8px; }.step-actions .inline-actions { flex-wrap: wrap; } }
</style>
