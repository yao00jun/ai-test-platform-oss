<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconCheckCircle, IconSave } from '@arco-design/web-vue/es/icon'
import { aiApi } from '../../api/ai'
import { RequestScope } from '../../core/request-scope'
import ErrorNotice from '../common/ErrorNotice.vue'
import ModelPricingEditor from './ModelPricingEditor.vue'

const visible = defineModel<boolean>('visible', { default: false })
const form = reactive({ baseUrl: '', apiKey: '', modelName: '' })
const hasApiKey = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref<unknown>()
const errors = ref<Record<string, string>>({})
const testResult = ref<{ ok: boolean; message: string }>()
const scope = new RequestScope()

watch(visible, async (open) => {
  if (!open) { scope.invalidate(); form.apiKey = ''; return }
  const token = scope.begin('settings')
  error.value = undefined
  errors.value = {}
  testResult.value = undefined
  loading.value = true
  busy.value = false
  try {
    const result = await aiApi.settings(token.signal)
    if (!scope.isCurrent(token)) return
    form.baseUrl = result.baseUrl
    form.modelName = result.modelName
    form.apiKey = ''
    hasApiKey.value = result.hasApiKey
  } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  finally { if (scope.isCurrent(token)) loading.value = false }
})

async function save(test = false) {
  errors.value = {}
  try { const url = new URL(form.baseUrl.trim()); if (!['https:', 'http:'].includes(url.protocol)) throw new Error() } catch { errors.value.baseUrl = '请输入有效的 HTTP 或 HTTPS 地址' }
  if (!form.modelName.trim()) errors.value.modelName = '请填写模型名称'
  if (!hasApiKey.value && !form.apiKey.trim()) errors.value.apiKey = '请填写 API Key'
  if (Object.keys(errors.value).length) return
  const token = scope.begin('settings-save')
  busy.value = true
  error.value = undefined
  testResult.value = undefined
  try {
    const settings = await aiApi.saveSettings({ baseUrl: form.baseUrl.trim(), modelName: form.modelName.trim(), ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}) })
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
        <a-form-item field="apiKey" label="API Key" label-component="label" :label-attrs="{ for: 'model-api-key' }" :required="!hasApiKey" :validate-status="errors.apiKey ? 'error' : undefined" :help="errors.apiKey || (hasApiKey ? '已保存密钥；留空保留现有密钥。' : '密钥仅用于后端模型调用。')"><a-input-password v-model="form.apiKey" :input-attrs="{ id: 'model-api-key', 'aria-label': 'API Key', autocomplete: 'new-password' }" :placeholder="hasApiKey ? '留空保留现有密钥' : '请输入 API Key'" /></a-form-item>
        <a-form-item field="modelName" label="模型名称" label-component="label" :label-attrs="{ for: 'model-name' }" required :validate-status="errors.modelName ? 'error' : undefined" :help="errors.modelName"><a-input v-model="form.modelName" :input-attrs="{ id: 'model-name', 'aria-label': '模型名称' }" placeholder="服务商提供的完整模型名称" /></a-form-item>
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
