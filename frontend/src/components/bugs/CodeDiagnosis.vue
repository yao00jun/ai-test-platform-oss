<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import type { Asset } from '../../api/types'
import { assetApi } from '../../api/assets'
import { bugApi, regressionLabels, verdictLabels, type FailureSourceEvidence, type RcaEvaluations, type RcaVerdict, type RegressionVerdict } from '../../api/bugs'
import { ApiError, isRecord, saveDownload } from '../../api/client'
import { codeDiagnosisDraft, compileCodeDiagnosis, rebaseCodeDiagnosisDraft } from '../../core/code-diagnosis-form'
import { RequestScope, type ScopeToken } from '../../core/request-scope'
import { formatTime } from '../../core/format'
import ErrorNotice from '../common/ErrorNotice.vue'
import FailureSourceEvidenceView from './FailureSourceEvidence.vue'

const AiDrawer = defineAsyncComponent(() => import('../ai/AiDrawer.vue'))
const props = defineProps<{ asset: Asset }>()
const emit = defineEmits<{ changed: [asset: Asset] }>()
const scope = new RequestScope(), reads = new RequestScope()
let current: ScopeToken | undefined
const baseline = ref(props.asset), initial = ref(codeDiagnosisDraft(props.asset.data.codeDiagnosis)), draft = ref({ ...initial.value })
const dirty = computed(() => JSON.stringify(draft.value) !== JSON.stringify(initial.value))
const latest = ref<Asset>(), error = ref<unknown>(), saving = ref(false), success = ref('')
const evidence = ref<FailureSourceEvidence>(), evaluations = ref<RcaEvaluations>(), loading = ref(false), readError = ref<unknown>(), page = ref(1)
const aiVisible = ref(false), actionError = ref<unknown>(), actionStatus = ref('')
const saved = computed(() => isRecord(props.asset.data.codeDiagnosis) ? props.asset.data.codeDiagnosis : {})
const savedPatch = computed(() => typeof saved.value.suggested_fix === 'string' ? saved.value.suggested_fix : '')
const evaluationBaseline = ref(props.asset), evaluationLatest = ref<Asset>(), evaluating = ref(false), evaluationError = ref<unknown>(), evaluationSuccess = ref('')
const blankEvaluation = () => ({ verdict: 'UNREVIEWED' as RcaVerdict, regression: 'UNREVIEWED' as RegressionVerdict, note: '' })
const evaluationDraft = ref(blankEvaluation()), evaluationInitial = ref(blankEvaluation())
const evaluationDirty = computed(() => JSON.stringify(evaluationDraft.value) !== JSON.stringify(evaluationInitial.value))
let pending: { identity: string; key: string } | undefined

function resetCode(asset: Asset) { baseline.value = asset; initial.value = codeDiagnosisDraft(asset.data.codeDiagnosis); draft.value = { ...initial.value }; latest.value = undefined }
watch([() => props.asset.projectId, () => props.asset.id], () => {
  scope.invalidate(); reads.invalidate(); resetCode(props.asset)
  evidence.value = undefined; evaluations.value = undefined; loading.value = false; saving.value = false; evaluating.value = false
  error.value = undefined; readError.value = undefined; actionError.value = undefined; evaluationError.value = undefined
  success.value = ''; actionStatus.value = ''; evaluationSuccess.value = ''; page.value = 1; aiVisible.value = false
  evaluationBaseline.value = props.asset; evaluationDraft.value = blankEvaluation(); evaluationInitial.value = blankEvaluation(); evaluationLatest.value = undefined; pending = undefined
  current = scope.begin(`${props.asset.projectId}:${props.asset.id}`); void load()
}, { immediate: true })
watch(() => props.asset, asset => {
  if (asset.id !== baseline.value.id || asset.projectId !== baseline.value.projectId) return
  if (!dirty.value) resetCode(asset)
  if (!evaluationDirty.value && !pending) evaluationBaseline.value = asset
  void load()
})
async function load() {
  const token = current, target = props.asset
  if (!token || !scope.isCurrent(token)) return
  const query = reads.begin(token.key); loading.value = true
  const results = await Promise.allSettled([
    bugApi.codeEvidence(target.projectId, target.id, query.signal),
    bugApi.evaluations(target.projectId, target.id, (page.value - 1) * 25, query.signal),
  ])
  if (!scope.isCurrent(token) || !reads.isCurrent(query) || props.asset.version !== target.version) return
  const [source, ratings] = results
  readError.value = undefined
  if (source.status === 'fulfilled') evidence.value = source.value; else readError.value = source.reason
  if (ratings.status === 'fulfilled') {
    evaluations.value = ratings.value
    if (!evaluationDirty.value && !pending) {
      const value = ratings.value.current
      evaluationDraft.value = value ? { verdict: value.verdict, regression: value.regression, note: value.note ?? '' } : blankEvaluation()
      evaluationInitial.value = { ...evaluationDraft.value }; evaluationBaseline.value = target
    }
  } else readError.value = ratings.reason
  loading.value = false
}
async function save() {
  const token = current, target = baseline.value
  if (!token || saving.value || !dirty.value) return
  error.value = undefined; success.value = ''; latest.value = undefined
  let code: Record<string, unknown>
  try { code = compileCodeDiagnosis(draft.value) } catch (failure) { error.value = failure; return }
  saving.value = true
  try {
    const updated = await assetApi.patch(target.projectId, target.id, { baseVersion: target.version, data: { codeDiagnosis: code } })
    if (!scope.isCurrent(token)) return
    resetCode(updated); emit('changed', updated); success.value = '代码诊断已保存'
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    error.value = failure
    if (failure instanceof ApiError && failure.status === 409) {
      try { const value = await assetApi.get(target.projectId, target.id, token.signal); if (scope.isCurrent(token)) latest.value = value }
      catch (readFailure) { if (scope.isCurrent(token)) readError.value = readFailure }
    }
  } finally { if (scope.isCurrent(token)) saving.value = false }
}
function rebase() {
  const asset = latest.value
  if (!asset) return
  const merged = rebaseCodeDiagnosisDraft(initial.value, draft.value, codeDiagnosisDraft(asset.data.codeDiagnosis))
  resetCode(asset); draft.value = merged; error.value = undefined; emit('changed', asset)
}
async function evaluate() {
  const token = current, target = evaluationBaseline.value
  if (!token || evaluating.value) return
  const body = { baseVersion: target.version, ...evaluationDraft.value }
  const identity = JSON.stringify({ projectId: target.projectId, bugId: target.id, ...body })
  if (pending?.identity !== identity) pending = { identity, key: crypto.randomUUID() }
  const request = pending
  evaluating.value = true; evaluationError.value = undefined; evaluationSuccess.value = ''; evaluationLatest.value = undefined
  try {
    await bugApi.evaluate(target.projectId, target.id, { ...body, idempotencyKey: request.key })
    if (!scope.isCurrent(token)) return
    pending = undefined; evaluationInitial.value = { ...evaluationDraft.value }; evaluationSuccess.value = '人工评价已保存'; void load()
  } catch (failure) {
    if (!scope.isCurrent(token)) return
    evaluationError.value = failure
    if (failure instanceof ApiError && failure.status === 409) {
      try { const value = await assetApi.get(target.projectId, target.id, token.signal); if (scope.isCurrent(token)) evaluationLatest.value = value }
      catch (readFailure) { if (scope.isCurrent(token)) readError.value = readFailure }
    }
  } finally { if (scope.isCurrent(token)) evaluating.value = false }
}
function rebaseEvaluation() {
  if (!evaluationLatest.value) return
  const target = evaluationLatest.value; evaluationBaseline.value = target; evaluationLatest.value = undefined
  pending = undefined; evaluationError.value = undefined; emit('changed', target)
}
async function copyPatch() {
  const token = current, patch = savedPatch.value
  actionError.value = undefined; actionStatus.value = ''
  try { await navigator.clipboard.writeText(patch); if (token && scope.isCurrent(token)) actionStatus.value = '补丁已复制' }
  catch { if (token && scope.isCurrent(token)) actionError.value = new Error('浏览器未允许复制，可下载补丁文件。') }
}
function downloadPatch() { saveDownload({ blob: new Blob([savedPatch.value], { type: 'text/x-diff;charset=utf-8' }), filename: `bug-${props.asset.id}.patch` }) }
function applied(asset: Asset) {
  if (asset.id !== props.asset.id || asset.projectId !== props.asset.projectId) return
  if (!dirty.value) resetCode(asset)
  emit('changed', asset)
}
onUnmounted(() => { scope.invalidate(); reads.invalidate() })
</script>

<template>
  <section class="code-diagnosis" role="region" aria-label="源码诊断">
    <header class="rca-heading"><div><h3>代码根因与修复建议</h3><p class="small muted">推测与人工结论分别保存，每次修改均可在版本历史中恢复。</p></div><a-button size="small" :loading="loading" @click="load">刷新诊断证据</a-button></header>
    <ErrorNotice :error="readError" retry @retry="load" />
    <details v-if="evidence" class="rca-evidence" open><summary>诊断时的固定源码</summary><FailureSourceEvidenceView :evidence="evidence" /></details>
    <form class="rca-form" aria-label="代码诊断编辑" @submit.prevent="save">
      <label class="wide">代码根因推测<textarea v-model="draft.root_cause" aria-label="代码根因推测" :disabled="saving" rows="4" maxlength="32000" /></label>
      <label class="wide">关联代码位置<input v-model="draft.affected_code_path" aria-label="关联代码位置" :disabled="saving" placeholder="src/main/java/OrderService.java:142"></label>
      <label>退化推测<select v-model="draft.is_regression" aria-label="退化推测" :disabled="saving"><option value="">未知</option><option value="true">可能是退化缺陷</option><option value="false">推测非退化</option></select></label>
      <label>置信度<input v-model="draft.confidence" aria-label="代码诊断置信度" inputmode="decimal" :disabled="saving" placeholder="0–1；留空为未知"></label>
      <label class="wide">建议补丁<textarea v-model="draft.suggested_fix" class="patch-input" aria-label="建议补丁" :disabled="saving" rows="7" spellcheck="false" /></label>
      <p v-if="baseline.version !== asset.version" class="small muted wide">这份草稿仍基于 v{{ baseline.version }}；最新记录为 v{{ asset.version }}，保存时会检查冲突。</p>
      <div class="inline-actions wide rca-actions"><a-button type="primary" html-type="submit" :loading="saving" :disabled="!dirty">保存代码诊断</a-button><a-button :disabled="saving || dirty" @click="aiVisible = true">🪄 局部 AI 调优代码诊断</a-button><span v-if="success" role="status" class="small success">{{ success }}</span></div>
      <p v-if="dirty" class="small muted wide">保存当前代码草稿后可继续 AI 调优。</p>
    </form>
    <ErrorNotice :error="error" />
    <div v-if="latest" class="rca-conflict"><p>最新记录为 v{{ latest.version }}。核对后可将本次编辑的字段合入最新内容。</p><details><summary>最新代码诊断</summary><pre class="json-view">{{ JSON.stringify(latest.data.codeDiagnosis ?? {}, null, 2) }}</pre></details><a-button size="small" @click="rebase">保留代码修改并使用最新版本</a-button></div>
    <section v-if="savedPatch" class="patch-preview" aria-label="建议补丁预览"><header><h4>已保存的补丁建议</h4><div class="inline-actions"><a-button size="small" @click="copyPatch">复制补丁</a-button><a-button size="small" @click="downloadPatch">下载补丁</a-button></div></header><p class="small muted">供人工审阅与使用；平台保存和导出建议文本。</p><pre data-testid="rca-patch" class="patch-text">{{ savedPatch }}</pre><span v-if="actionStatus" role="status" class="small success">{{ actionStatus }}</span><ErrorNotice :error="actionError" /></section>
    <section class="human-evaluation" aria-label="人工 RCA 评价">
      <h3>人工评价</h3><p class="small muted">评价关联当前已保存的诊断内容。诊断变化后需重新评价，历史判断仍然保留。</p>
      <p v-if="evaluations" data-testid="rca-current-evaluation">当前诊断：{{ evaluations.current ? verdictLabels[evaluations.current.verdict] : '未评价' }} · {{ evaluations.current ? regressionLabels[evaluations.current.regression] : '未确认退化' }}</p>
      <form class="rca-form" aria-label="人工评价编辑" @submit.prevent="evaluate"><label>RCA 人工评价<select v-model="evaluationDraft.verdict" aria-label="RCA 人工评价" :disabled="evaluating"><option v-for="(label, value) in verdictLabels" :key="value" :value="value">{{ label }}</option></select></label><label>退化缺陷人工确认<select v-model="evaluationDraft.regression" aria-label="退化缺陷人工确认" :disabled="evaluating"><option v-for="(label, value) in regressionLabels" :key="value" :value="value">{{ label }}</option></select></label><label class="wide">评价备注<textarea v-model="evaluationDraft.note" aria-label="评价备注" :disabled="evaluating" rows="3" maxlength="4000" /></label><div class="inline-actions wide rca-actions"><a-button html-type="submit" :loading="evaluating" :disabled="dirty || saving || !evaluations">保存人工评价</a-button><span v-if="evaluationSuccess" role="status" class="small success">{{ evaluationSuccess }}</span></div></form>
      <ErrorNotice :error="evaluationError" /><div v-if="evaluationLatest" class="rca-conflict"><p>记录已变更，请核对 v{{ evaluationLatest.version }} 的诊断后再提交这份评价。</p><a-button size="small" @click="rebaseEvaluation">已核对最新诊断，保留评价继续</a-button></div>
      <h4 v-if="evaluations">评价历史 · {{ evaluations.total }}</h4><article v-for="item in evaluations?.items ?? []" :key="item.id" class="evaluation-record" :aria-label="`人工评价 ${item.id}`"><header><strong>{{ verdictLabels[item.verdict] }} · {{ regressionLabels[item.regression] }}</strong><span class="small muted">{{ formatTime(item.createdAt) }}</span></header><p>{{ item.note || '未填写备注' }}</p><p class="small muted">诊断版本 v{{ item.assetVersion }} · {{ item.actor }} · {{ item.source }}</p><details><summary>诊断摘要</summary><code>{{ item.diagnosisHash }}</code></details></article><a-pagination v-if="evaluations && evaluations.total > 25" v-model:current="page" :total="evaluations.total" :page-size="25" @change="load" />
    </section>
    <AiDrawer v-if="aiVisible" v-model:visible="aiVisible" :target="asset" :target-fields="['codeDiagnosis']" scope-label="当前缺陷的代码诊断与补丁" @applied="applied" />
  </section>
</template>

<style scoped>
.code-diagnosis { min-width: 0; overflow-wrap: anywhere; }.rca-heading, .patch-preview header, .evaluation-record header { display: flex; align-items: start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }h3 { font-size: 15px; margin: 0 0 10px; }h4 { font-size: 13px; margin: 0; }.rca-evidence { margin-block: 18px; }summary { cursor: pointer; font-size: 12px; }.rca-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding: 16px; border: 1px solid var(--border); border-radius: 8px; margin-block: 16px; background: var(--surface-subtle); }.rca-form label { display: grid; gap: 7px; font-size: 12px; }.rca-form input, .rca-form textarea, .rca-form select { box-sizing: border-box; width: 100%; min-width: 0; padding: 9px; border: 1px solid var(--border); border-radius: 5px; background: var(--surface); color: var(--text); font: inherit; }.rca-form textarea { resize: vertical; line-height: 1.7; }.rca-form .patch-input, .patch-text { font: 12px/1.8 ui-monospace, Consolas, monospace; }.wide { grid-column: 1 / -1; }.rca-actions { flex-wrap: wrap; }.success { color: var(--success); }.rca-conflict { border-left: 3px solid var(--warning-border); padding: 12px; margin-block: 12px; font-size: 12px; }.patch-preview, .human-evaluation { margin-top: 24px; }.patch-text { padding: 14px; background: var(--surface-code); border: 1px solid var(--border); border-radius: 8px; white-space: pre; max-height: 420px; overflow: auto; }.evaluation-record { border-top: 1px solid var(--border); margin-top: 14px; padding-top: 14px; font-size: 12px; }.evaluation-record p { white-space: pre-wrap; }.evaluation-record details code { display: block; margin-top: 10px; word-break: break-all; }@media (max-width: 560px) { .rca-form { grid-template-columns: minmax(0, 1fr); padding: 12px; } }
</style>
