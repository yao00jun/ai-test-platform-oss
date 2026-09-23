<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { evalopsApi, type ModelPrice } from '../../api/evalops'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'

const props = defineProps<{ modelName: string; active: boolean }>()
const saved = ref<ModelPrice>(), busy = ref(false), loading = ref(false), error = ref<unknown>(), message = ref('')
const form = reactive({ enabled: false, currency: 'CNY', input: '', output: '' })
const scope = new RequestScope()
const currentName = computed(() => props.modelName.trim())
const matches = computed(() => saved.value?.modelName === currentName.value)
const dirty = computed(() => !!saved.value && (form.enabled !== saved.value.enabled || form.currency !== saved.value.currency || form.input !== String(saved.value.inputPerMillion ?? '') || form.output !== String(saved.value.outputPerMillion ?? '')))
function assign(price: ModelPrice) { saved.value = price; Object.assign(form, { enabled: price.enabled, currency: price.currency, input: String(price.inputPerMillion ?? ''), output: String(price.outputPerMillion ?? '') }) }
async function load() {
  if (!props.active || !currentName.value) return
  const token = scope.begin(currentName.value); loading.value = true; error.value = undefined; message.value = ''
  try { const price = await evalopsApi.price(token.key, token.signal); if (scope.isCurrent(token)) assign(price) }
  catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
}
async function save() {
  if (!saved.value || !matches.value || busy.value) return
  const token = scope.begin(currentName.value); busy.value = true; error.value = undefined; message.value = ''
  try {
    const result = await evalopsApi.savePrice({ modelName: token.key, baseVersion: saved.value.version, enabled: form.enabled, currency: form.currency.trim().toUpperCase(), inputPerMillion: form.enabled ? form.input : form.input || '0', outputPerMillion: form.enabled ? form.output : form.output || '0' })
    if (scope.isCurrent(token)) { assign(result); message.value = '价目已保存；仅用于后续调用，历史估算保持原价目。' }
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
watch(() => [props.active, currentName.value] as const, ([open]) => {
  scope.invalidate(); loading.value = false; busy.value = false
  if (open && (!saved.value || !dirty.value)) void load()
}, { immediate: true })
onUnmounted(() => scope.invalidate())
</script>

<template>
  <section aria-label="模型计价" class="pricing-editor">
    <h3>模型计价</h3>
    <p class="small muted">{{ currentName || '填写模型名称后设置价目' }}<template v-if="saved"> · 版本 {{ saved.version }}</template></p>
    <p class="small muted">按供应商实际报告的输入、输出 Token 估算。未报告用量时不计算费用；折扣、缓存与其他计费规则需以供应商账单核对。货币代码不限于 ISO 币种，可填写服务商自己的计费单位，费用按代码分开汇总。</p>
    <ErrorNotice :error="error" />
    <a-alert v-if="saved && !matches" type="warning">模型名称已改变，请载入当前模型价目后再保存。</a-alert>
    <fieldset :disabled="loading || busy || !matches" class="price-fields">
      <label class="price-toggle"><input v-model="form.enabled" type="checkbox" aria-label="启用成本估算">启用成本估算</label>
      <label>货币代码<input v-model="form.currency" aria-label="货币代码" class="arco-input" maxlength="16" placeholder="CNY、USD 或服务商自定义单位，如 POINTS"></label>
      <label>输入单价（每百万 Token）<input v-model="form.input" aria-label="输入单价（每百万 Token）" class="arco-input" inputmode="decimal" placeholder="例如 2"></label>
      <label>输出单价（每百万 Token）<input v-model="form.output" aria-label="输出单价（每百万 Token）" class="arco-input" inputmode="decimal" placeholder="例如 3"></label>
    </fieldset>
    <p v-if="message" role="status" class="small">{{ message }}</p>
    <p v-if="dirty" class="small muted">价目有未保存修改。重新载入会放弃这些修改。</p>
    <div class="inline-actions"><a-button :loading="loading" :disabled="busy || !currentName" @click="load">重新载入价目</a-button><a-button :loading="busy" :disabled="loading || !matches" @click="save">保存价目</a-button></div>
  </section>
</template>

<style scoped>
.pricing-editor { margin-top: 24px; border-top: 1px solid var(--color-border-2); padding-top: 18px; }
.pricing-editor h3 { margin: 0 0 8px; }
.price-fields { border: 0; padding: 0; margin: 16px 0; display: grid; gap: 12px; min-width: 0; }
.price-fields label { display: grid; gap: 6px; font-size: 13px; }
.price-fields input.arco-input { width: 100%; border: 1px solid var(--color-border-2); padding: 7px 10px; border-radius: 6px; }
.price-fields .price-toggle { display: flex; align-items: center; gap: 8px; }
</style>
