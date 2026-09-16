<script setup lang="ts">
import type { FailureSourceEvidence } from '../../api/bugs'
defineProps<{ evidence: FailureSourceEvidence }>()
</script>

<template>
  <section class="failure-source" aria-label="固定源码证据">
    <p class="small muted">片段来自本次运行绑定的源码快照。堆栈命中表示定位依据，根因仍需核实。</p>
    <p v-if="!evidence.locations.length" class="small muted">没有可定位的源码证据。</p>
    <div v-for="binding in evidence.bindings" :key="binding.sourceSnapshotId" class="source-binding"><span>快照 {{ binding.sourceSnapshotId }}</span><code>{{ binding.manifestHash }}</code></div>
    <article v-for="location in evidence.locations" :key="location.id" :aria-label="`源码位置 ${location.path}:${location.line}`" class="source-location">
      <h4>{{ location.path }}:{{ location.line }}</h4><p class="small muted">{{ location.className }}.{{ location.method }} · 第 {{ location.from }}–{{ location.to }} 行</p><pre class="source-lines">{{ location.content }}</pre><p class="small muted source-hash">SHA-256 {{ location.sha256 }}</p>
    </article>
    <details v-for="(diff, index) in evidence.diffs" :key="`${diff.sourceSnapshotId}:${diff.path}:${index}`"><summary>运行基线差异 · {{ diff.path }}</summary><pre class="source-lines">{{ diff.diff }}</pre></details>
    <ul v-if="evidence.diagnostics.length" class="source-diagnostics"><li v-for="(item, index) in evidence.diagnostics" :key="`${item.code}:${index}`"><code>{{ item.code }}</code> · {{ item.message }}</li></ul>
    <p v-if="!evidence.complete" class="small muted">源码证据不完整，以上缺口会随诊断记录保留。</p>
  </section>
</template>

<style scoped>
.failure-source { min-width: 0; overflow-wrap: anywhere; }.source-binding { display: grid; gap: 5px; font-size: 11px; margin-block: 12px; color: var(--muted); }.source-location { border: 1px solid var(--border); border-radius: 8px; padding: 14px; margin-block: 12px; }h4 { font-size: 13px; margin: 0; }.source-lines { font: 12px/1.8 ui-monospace, Consolas, monospace; white-space: pre; overflow: auto; max-height: 400px; padding: 12px; border-radius: 6px; background: var(--surface-code); }.source-hash { word-break: break-all; }.source-diagnostics { padding-left: 18px; font-size: 12px; line-height: 1.9; }summary { font-size: 12px; cursor: pointer; }
</style>
