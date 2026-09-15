<script setup lang="ts">
import type { PipelineSourcePreparation } from '../../core/pipeline-preparation'
import SourceSnapshotSelect from '../analysis/SourceSnapshotSelect.vue'

defineProps<{ projectId: string; disabled?: boolean; failed?: boolean }>()
const source = defineModel<PipelineSourcePreparation>({ required: true })
function retry() { source.value = { ...source.value, request: undefined, submission: undefined, snapshotId: undefined } }
</script>

<template>
  <section class="pipeline-source-fields" aria-label="源码与数据库结构">
    <h3>3–5. 后端源码、前端源码与 DDL</h3>
    <label>源码依据<select v-model="source.mode" aria-label="源码依据" :disabled="disabled"><option value="none">本次只使用需求和接口文档</option><option value="existing">使用已分析的固定快照</option><option value="prepare">读取源码路径与 DDL 后自动生成</option></select></label>
    <SourceSnapshotSelect v-if="source.mode === 'existing'" v-model="source.snapshotId" :project-id="projectId" :disabled="disabled" />
    <template v-if="source.mode === 'prepare'">
      <div class="source-input-grid">
        <label>后端源码路径<input v-model="source.fields.backendRepoPath" aria-label="后端源码路径" :disabled="disabled || !!source.request" placeholder="Java 项目绝对路径或 Git 地址"></label>
        <label>前端源码路径<input v-model="source.fields.frontendRepoPath" aria-label="前端源码路径" :disabled="disabled || !!source.request" placeholder="Vue / React 目录或 Git 地址"></label>
        <label class="wide">SQL 脚本路径<input v-model="source.fields.sqlScriptPath" aria-label="SQL 脚本路径" :disabled="disabled || !!source.request" placeholder="本地 .sql 文件绝对路径"></label>
        <label class="wide">补充 DDL<textarea v-model="source.fields.ddlText" aria-label="补充 DDL" rows="4" :disabled="disabled || !!source.request" placeholder="也可以粘贴 CREATE TABLE 建表定义"></textarea></label>
      </div>
      <details><summary>指定 Git 版本</summary><div class="source-input-grid"><label>后端 Git 版本<input v-model="source.fields.backendRef" aria-label="后端 Git 版本" :disabled="disabled || !!source.request" placeholder="可选提交、分支或标签"></label><label>前端 Git 版本<input v-model="source.fields.frontendRef" aria-label="前端 Git 版本" :disabled="disabled || !!source.request" placeholder="可选提交、分支或标签"></label><label>对比基线版本<input v-model="source.fields.baselineRef" aria-label="对比基线版本" :disabled="disabled || !!source.request" placeholder="可选，用于定向回归"></label></div></details>
      <p class="small muted">本地路径由后端服务读取。采集完成后自动接续生成，所有阶段和反馈使用同一份固定源码。DDL 只用于结构校验；SQL 执行前需要绑定业务数据库。</p>
      <p v-if="source.request" class="small muted">已保留本次采集输入和任务身份，继续提交会恢复同一次分析。</p>
      <a-button v-if="source.request && !disabled" size="small" @click="retry">{{ failed ? '重新采集源码' : '更换采集输入' }}</a-button>
    </template>
  </section>
</template>

<style scoped>
.pipeline-source-fields { display: grid; gap: 16px; margin-block: 24px; padding-bottom: 24px; border-bottom: 1px solid var(--border); min-width: 0; }h3 { font-size: 16px; margin: 0; }label { display: grid; gap: 8px; font-size: 13px; }input, textarea, select { box-sizing: border-box; width: 100%; min-width: 0; padding: 9px 12px; border: 1px solid #cbd1df; border-radius: 6px; background: white; color: var(--text); font: inherit; }input:disabled, textarea:disabled, select:disabled { background: #f7f8fa; }.source-input-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }.wide { grid-column: 1 / -1; }summary { font-size: 12px; color: var(--muted); cursor: pointer; margin-bottom: 14px; }p { margin: 0; line-height: 1.7; overflow-wrap: anywhere; }textarea { resize: vertical; }@media(max-width: 600px) { .source-input-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
