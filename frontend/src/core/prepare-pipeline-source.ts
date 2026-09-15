import { analysisApi, sourceStatusLabels, type SourceAnalysis } from '../api/analysis'
import type { PipelineSourcePreparation } from './pipeline-preparation'

/** A lost POST reply is retried with frozen inputs; late replies persist only to the original preparation. */
export async function preparePipelineSource(project: string, initial: PipelineSourcePreparation, persist: (state: PipelineSourcePreparation) => void, signal: AbortSignal, status: (value: SourceAnalysis) => void): Promise<string | undefined> {
  let state = JSON.parse(JSON.stringify(initial)) as PipelineSourcePreparation
  signal.throwIfAborted()
  if (state.mode === 'none') return undefined
  if (state.mode === 'prepare') {
    if (![state.fields.backendRepoPath, state.fields.frontendRepoPath, state.fields.sqlScriptPath, state.fields.ddlText].some(value => value.trim())) throw new Error('请至少填写一项源码路径或 DDL。')
    if (!state.request) { state = { ...state, request: { ...state.fields, idempotencyKey: crypto.randomUUID() }, submission: undefined, snapshotId: undefined }; persist(state) }
    if (!state.submission) {
      const submission = await analysisApi.submit(project, state.request!)
      state = { ...state, submission, snapshotId: submission.analysisId }; persist(state)
    }
  }
  if (!state.snapshotId) throw new Error('请选择已完成分析的源码快照。')
  for (let attempt = 0; attempt < 400; attempt++) {
    signal.throwIfAborted()
    const snapshot = await analysisApi.get(project, state.snapshotId, signal); status(snapshot)
    if (['READY', 'PARTIAL'].includes(snapshot.status)) return snapshot.id
    if (['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(snapshot.status)) throw new Error(`源码分析${sourceStatusLabels[snapshot.status] ?? snapshot.status}：${snapshot.error ?? '请检查路径或解析诊断后重新采集。'}`)
    await delay(signal)
  }
  throw new Error('源码分析仍在进行，任务身份已保存；稍后继续可恢复同一次分析。')
}
function delay(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, 750)
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
  })
}
