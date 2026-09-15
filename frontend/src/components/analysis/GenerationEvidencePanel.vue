<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { analysisApi, type SourceExcerpt } from '../../api/analysis'
import { isRecord } from '../../api/client'
import type { Asset } from '../../api/types'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ asset: Asset }>()
const evidence = computed(() => record(props.asset.data.generationEvidence))
const query = computed(() => record(evidence.value.query)), ui = computed(() => record(evidence.value.ui))
const locators = computed(() => objects(ui.value.locators))
const source = computed(() => String(props.asset.data.sourceSnapshotId ?? ''))
const excerpt = ref<SourceExcerpt>(), reading = ref(false), error = ref<unknown>()
const scope = new RequestScope()
function record(value: unknown): Record<string, unknown> { return isRecord(value) ? value : {} }
function objects(value: unknown): Record<string, unknown>[] { return Array.isArray(value) ? value.filter(isRecord) : [] }
function labels(value: unknown) { return Array.isArray(value) ? value.map(String).join('、') : '' }
watch(() => [props.asset.projectId, props.asset.id, props.asset.version], () => { scope.invalidate(); excerpt.value = undefined; error.value = undefined; reading.value = false })
async function read(locator: Record<string, unknown>) {
  const token = scope.begin(`${props.asset.projectId}:${props.asset.id}:${props.asset.version}`)
  reading.value = true; error.value = undefined
  try {
    const result = await analysisApi.excerpt(props.asset.projectId, source.value, { kind: 'FRONTEND', path: String(locator.sourcePath) }, Math.max(1, Number(locator.line) - 5), token.signal)
    if (scope.isCurrent(token)) excerpt.value = result
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) reading.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <section v-if="source" class="generation-evidence" aria-label="生成依据">
    <header><h3>生成依据</h3><a-tag size="small">固定源码</a-tag></header>
    <p class="small muted">源码快照 <code>{{ source }}</code><template v-if="evidence.manifestHash"><br>文件摘要 <code>{{ evidence.manifestHash }}</code></template></p>
    <p v-if="evidence.status === 'PARTIAL'" class="evidence-warning">此快照包含部分解析结果；未解析内容可在项目源码分析中查看。</p>
    <template v-if="asset.type === 'SQL_VALIDATION'">
      <p v-if="!asset.data.databaseSourceId" class="evidence-warning">等待绑定业务数据库。SQL 草稿可以手工编辑；配置完成前，计划不会发起执行。</p>
      <p v-if="query.tables" class="small">依据表：{{ labels(query.tables) }}<br>返回列：{{ labels(query.outputColumns) }}</p>
      <p v-if="evidence.liveSchema" class="small muted">生成时已校验业务库 Schema 与 EXPLAIN。修改 SQL 后按当前配置重新验证。</p>
    </template>
    <p v-if="ui.requiresRuntimeVerification" class="evidence-warning">静态定位依据 · 待运行验证。条件渲染和实际页面状态以运行结果为准。</p>
    <p v-if="labels(ui.blockedReasons).includes('WEB_BASE_URL_REQUIRED')" class="small muted">生成时缺少页面地址。请为场景配置页面地址和导航步骤后执行。</p>
    <ul v-if="locators.length" class="locator-evidence"><li v-for="(locator, index) in locators" :key="index"><code>{{ locator.selector }}</code><span class="small muted">{{ locator.field === 'targetSelector' ? '拖放目标' : '目标定位' }} · {{ locator.evidenceLevel === 'STATIC' ? '源码属性' : locator.evidenceLevel === 'OBSERVED' ? '实际页面观察' : '录制记录' }}</span><button v-if="locator.sourcePath" type="button" :disabled="reading" @click="read(locator)">{{ locator.sourcePath }}:{{ locator.line }} · 查看固定源码</button></li></ul>
    <ErrorNotice :error="error" />
    <div v-if="excerpt" class="evidence-excerpt"><strong>{{ excerpt.path }} · {{ excerpt.from }}–{{ excerpt.to }} 行</strong><pre>{{ excerpt.content }}</pre></div>
    <p v-if="!Object.keys(evidence).length" class="small muted">此资产由人工绑定源码，尚无 AI 生成时的查询或页面校验记录。</p>
  </section>
</template>

<style scoped>
.generation-evidence { min-width: 0; margin-bottom: 22px; padding: 16px; border: 1px solid #dce4f2; border-radius: 8px; background: #f8faff; overflow-wrap: anywhere; }header { display: flex; justify-content: space-between; gap: 12px; }h3 { font-size: 14px; margin: 0; }p { line-height: 1.7; margin: 10px 0 0; }.evidence-warning { color: #976414; font-size: 12px; }.locator-evidence { list-style: none; padding: 0; margin: 12px 0 0; display: grid; gap: 10px; }.locator-evidence li { display: grid; gap: 5px; padding-top: 10px; border-top: 1px solid #e3e8f2; }button { text-align: left; color: var(--primary); font: inherit; font-size: 12px; background: none; border: 0; padding: 0; cursor: pointer; overflow-wrap: anywhere; }.evidence-excerpt { margin-top: 14px; font-size: 12px; }pre { max-height: 250px; overflow: auto; padding: 12px; background: #eef2f8; white-space: pre-wrap; line-height: 1.7; }
</style>
