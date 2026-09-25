<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconCheckCircle, IconRefresh, IconSave } from '@arco-design/web-vue/es/icon'
import { aiApi } from '../../api/ai'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import ModelPricingEditor from './ModelPricingEditor.vue'

const visible = defineModel<boolean>('visible', { default: false })
const form = reactive({ baseUrl: '', apiKey: '', modelName: '', trustSelfSigned: false, timeoutSeconds: 600, temperature: 0.3, requestsPerMinute: 0 })
const isHttps = computed(() => form.baseUrl.trim().toLowerCase().startsWith('https://'))
const hasApiKey = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref<unknown>()
const errors = ref<Record<string, string>>({})
const testResult = ref<{ ok: boolean; message: string }>()
const models = ref<string[]>([])
const modelsLoading = ref(false)
const modelsMessage = ref('')
const scope = new RequestScope()

watch(visible, async (open) => {
  if (!open) { scope.invalidate(); form.apiKey = ''; return }
  const token = scope.begin('settings')
  error.value = undefined
  errors.value = {}
  testResult.value = undefined
  models.value = []
  modelsMessage.value = ''
  modelsLoading.value = false
  loading.value = true
  busy.value = false
  try {
    const result = await aiApi.settings(token.signal)
    if (!scope.isCurrent(token)) return
    form.baseUrl = result.baseUrl
    form.modelName = result.modelName
    form.trustSelfSigned = result.trustSelfSigned
    form.timeoutSeconds = result.timeoutSeconds
    form.temperature = result.temperature
    form.requestsPerMinute = result.requestsPerMinute
    form.apiKey = ''
    hasApiKey.value = result.hasApiKey
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
})

async function fetchModels() {
  errors.value = {}
  try { const url = new URL(form.baseUrl.trim()); if (!['https:', 'http:'].includes(url.protocol)) throw new Error() } catch { errors.value.baseUrl = '请输入有效的 HTTP 或 HTTPS 地址' }
  if (!hasApiKey.value && !form.apiKey.trim()) errors.value.apiKey = '请先填写 API Key 再获取模型列表'
  if (Object.keys(errors.value).length) return
  const token = scope.begin('settings-models')
  modelsLoading.value = true
  error.value = undefined
  modelsMessage.value = ''
  try {
    const result = await aiApi.listModels({ baseUrl: form.baseUrl.trim(), trustSelfSigned: form.trustSelfSigned, ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}) }, token.signal)
    if (!scope.isCurrent(token)) return
    models.value = result.models
    modelsMessage.value = result.models.length ? `已获取 ${result.models.length} 个模型，可下拉选择或继续手填。` : '服务返回了空的模型列表，请手动填写模型名称。'
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) modelsLoading.value = false }
}

async function save(test = false) {
  errors.value = {}
  try { const url = new URL(form.baseUrl.trim()); if (!['https:', 'http:'].includes(url.protocol)) throw new Error() } catch { errors.value.baseUrl = '请输入有效的 HTTP 或 HTTPS 地址' }
  if (!form.modelName.trim()) errors.value.modelName = '请填写模型名称'
  if (!hasApiKey.value && !form.apiKey.trim()) errors.value.apiKey = '请填写 API Key'
  if (!Number.isInteger(form.timeoutSeconds) || form.timeoutSeconds < 5 || form.timeoutSeconds > 3600) errors.value.timeoutSeconds = '请填写 5–3600 之间的整数秒数'
  if (typeof form.temperature !== 'number' || Number.isNaN(form.temperature) || form.temperature < 0 || form.temperature > 2) errors.value.temperature = '请填写 0–2 之间的数值'
  if (!Number.isInteger(form.requestsPerMinute) || form.requestsPerMinute < 0 || form.requestsPerMinute > 10000) errors.value.requestsPerMinute = '请填写 0–10000 之间的整数，0 表示不限制'
  if (Object.keys(errors.value).length) return
  const token = scope.begin('settings-save')
  busy.value = true
  error.value = undefined
  testResult.value = undefined
  try {
    const settings = await aiApi.saveSettings({ baseUrl: form.baseUrl.trim(), modelName: form.modelName.trim(), trustSelfSigned: form.trustSelfSigned, timeoutSeconds: form.timeoutSeconds, temperature: form.temperature, requestsPerMinute: form.requestsPerMinute, ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}) })
    if (!scope.isCurrent(token)) return
    form.apiKey = ''
    hasApiKey.value = settings.hasApiKey
    if (test) {
      const result = await aiApi.testSettings()
      if (scope.isCurrent(token)) testResult.value = result
    } else { Message.success('模型设置已保存'); visible.value = false }
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) busy.value = false }
}
onUnmounted(() => scope.invalidate())
</script>

<template>
  <a-drawer v-model:visible="visible" title="模型设置" role="dialog" aria-modal="true" aria-label="模型设置" :width="480" :mask-closable="!busy" unmount-on-close>
    <p class="settings-intro">配置兼容 OpenAI 接口的模型服务。保存后可发起真实连接测试，测试结果不会写入测试资产。</p>
    <a-spin :loading="loading" style="width: 100%">
      <a-form :model="form" layout="vertical" :disabled="busy || loading">
        <a-form-item field="baseUrl" label="Base URL" label-component="label" :label-attrs="{ for: 'model-base-url' }" required :validate-status="errors.baseUrl ? 'error' : undefined" :help="errors.baseUrl"><a-input v-model="form.baseUrl" :input-attrs="{ id: 'model-base-url', 'aria-label': 'Base URL' }" placeholder="https://api.example.com/v1" /></a-form-item>
        <a-form-item v-if="isHttps || form.trustSelfSigned" field="trustSelfSigned" :help="form.trustSelfSigned ? '已跳过证书校验：只在公司内网地址上使用，不要对公网服务开启。' : '公司内部或自签名证书的 https 服务报 TLS 握手失败时勾选；公网服务保持关闭。'">
          <a-checkbox v-model="form.trustSelfSigned">信任内部/自签名证书</a-checkbox>
        </a-form-item>
        <a-form-item field="apiKey" label="API Key" label-component="label" :label-attrs="{ for: 'model-api-key' }" :required="!hasApiKey" :validate-status="errors.apiKey ? 'error' : undefined" :help="errors.apiKey || (hasApiKey ? '已保存密钥；留空保留现有密钥。' : '密钥仅用于后端模型调用。')"><a-input-password v-model="form.apiKey" :input-attrs="{ id: 'model-api-key', 'aria-label': 'API Key', autocomplete: 'new-password' }" :placeholder="hasApiKey ? '留空保留现有密钥' : '请输入 API Key'" /></a-form-item>
        <a-form-item field="modelName" label="模型名称" label-component="label" :label-attrs="{ for: 'model-name' }" required :validate-status="errors.modelName ? 'error' : undefined" :help="errors.modelName || modelsMessage || '可点击右侧按钮从服务商获取模型列表，也可以直接手填。'">
          <div class="model-name-row">
            <a-auto-complete v-model="form.modelName" :data="models" :input-attrs="{ id: 'model-name', 'aria-label': '模型名称' }" placeholder="服务商提供的完整模型名称" allow-clear />
            <a-button :loading="modelsLoading" :disabled="busy || loading" aria-label="获取模型列表" title="从服务商获取模型列表" @click="fetchModels"><template #icon><IconRefresh /></template>获取</a-button>
          </div>
        </a-form-item>
        <a-form-item field="timeoutSeconds" label="单次调用超时（秒）" label-component="label" :label-attrs="{ for: 'model-timeout' }" :validate-status="errors.timeoutSeconds ? 'error' : undefined" :help="errors.timeoutSeconds || '一次模型调用从发出到全部返回的最长时间，默认 600。生成内容多、模型较慢（如推理模型）时调大；流水线提示“超过 N 秒上限”时改这里。'">
          <a-input-number v-model="form.timeoutSeconds" :min="5" :max="3600" :step="60" :precision="0" :input-attrs="{ id: 'model-timeout', 'aria-label': '单次调用超时（秒）' }" />
        </a-form-item>
        <a-form-item field="requestsPerMinute" label="每分钟最多请求数" label-component="label" :label-attrs="{ for: 'model-rpm' }" :validate-status="errors.requestsPerMinute ? 'error' : undefined" :help="errors.requestsPerMinute || '服务商限制每分钟请求数（RPM）时填写，例如 5；平台所有 AI 功能共用这个名额并自动排队，不会因此报限流错误。0 表示不限制。'">
          <a-input-number v-model="form.requestsPerMinute" :min="0" :max="10000" :step="1" :precision="0" :input-attrs="{ id: 'model-rpm', 'aria-label': '每分钟最多请求数' }" />
        </a-form-item>
        <a-form-item field="temperature" label="温度" label-component="label" :label-attrs="{ for: 'model-temperature' }" :validate-status="errors.temperature ? 'error' : undefined" :help="errors.temperature || '0–2，越低输出越稳定。生成测试资产建议 0.1–0.3，默认 0.3。'">
          <a-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" :input-attrs="{ id: 'model-temperature', 'aria-label': '温度' }" />
        </a-form-item>
      </a-form>
    </a-spin>
    <ErrorNotice :error="error" />
    <a-alert v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :title="testResult.ok ? '连接成功' : '连接失败'" style="margin-top: 16px">{{ testResult.message }}</a-alert>
    <ModelPricingEditor :model-name="form.modelName" :active="visible && !loading" />
    <template #footer>
      <div class="inline-actions" style="justify-content: flex-end">
        <a-button :loading="busy" :disabled="loading" @click="save(true)"><template #icon><IconCheckCircle /></template>保存并测试</a-button>
        <a-button type="primary" :loading="busy" :disabled="loading" @click="save(false)"><template #icon><IconSave /></template>保存设置</a-button>
      </div>
    </template>
  </a-drawer>
</template>

<style scoped>
.model-name-row { display: flex; gap: 8px; align-items: flex-start; }
.model-name-row :deep(.arco-auto-complete), .model-name-row :deep(.arco-input-wrapper) { flex: 1; min-width: 0; }
</style>
