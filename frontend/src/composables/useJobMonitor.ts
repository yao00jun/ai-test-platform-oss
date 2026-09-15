import { computed, onUnmounted, ref } from 'vue'
import { aiApi } from '../api/ai'
import { ApiError, isRecord } from '../api/client'
import { JobStream } from '../api/job-stream'
import type { Job } from '../api/types'
import { RequestScope, type ScopeToken } from '../core/request-scope'

export const terminalJobStates = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'])
export function jobFailure(job: Job): ApiError {
  let detail: unknown = job.error
  if (typeof detail === 'string') { try { detail = JSON.parse(detail) } catch { detail = { message: detail } } }
  const value = isRecord(detail) ? detail : {}
  return new ApiError(422, String(value.code ?? job.status), String(value.message ?? job.message ?? '任务未完成'), value.details)
}

/** Persisted GET and SSE cooperate; a bounded poll recovers a lost terminal event. */
export function useJobMonitor() {
  const job = ref<Job>()
  const error = ref<unknown>()
  const streamedText = ref('')
  const reconnecting = ref(false)
  const loading = ref(false)
  const active = computed(() => !!job.value && !terminalJobStates.has(job.value.status))
  const scope = new RequestScope(), stream = new JobStream()
  let current: ScopeToken | undefined, project = '', id = ''
  let timer: ReturnType<typeof setTimeout> | undefined
  let reading = false

  function stop() {
    scope.invalidate(); stream.close(); clearTimeout(timer); timer = undefined
    current = undefined; reading = false; loading.value = false; reconnecting.value = false
    job.value = undefined; error.value = undefined; streamedText.value = ''
  }
  async function refresh() {
    const token = current
    if (!token || reading) return
    reading = true
    try {
      const result = await aiApi.job(project, id, token.signal)
      if (!scope.isCurrent(token)) return
      job.value = result; error.value = undefined
      if (terminalJobStates.has(result.status)) { stream.close(); clearTimeout(timer); reconnecting.value = false }
    } catch (failure) { if (scope.isCurrent(token)) { error.value = failure; reconnecting.value = true } }
    finally {
      if (scope.isCurrent(token)) {
        reading = false; loading.value = false; clearTimeout(timer)
        if (!job.value || !terminalJobStates.has(job.value.status)) timer = setTimeout(() => { void refresh() }, 2000)
      }
    }
  }
  async function start(projectId: string, jobId: string) {
    stop(); project = projectId; id = jobId
    current = scope.begin(`${projectId}:${jobId}`)
    const token = current
    job.value = undefined; error.value = undefined; streamedText.value = ''; loading.value = true
    stream.subscribe({ projectId, jobId }, event => {
      if (!scope.isCurrent(token)) return
      reconnecting.value = false
      const data = isRecord(event.data.payload) ? { ...event.data, ...event.data.payload } : event.data
      if (event.type === 'token') {
        const text = data.token ?? data.text ?? data.content
        if (typeof text === 'string') streamedText.value = (streamedText.value + text).slice(-100000)
      }
      if (event.type === 'done' || event.type === 'error' || event.type === 'progress') void refresh()
    }, () => { if (scope.isCurrent(token)) { reconnecting.value = true; void refresh() } })
    await refresh()
  }
  async function cancel() {
    const token = current
    if (!token) return
    try {
      const result = await aiApi.cancel(project, id)
      if (scope.isCurrent(token)) { job.value = result; stream.close(); clearTimeout(timer) }
    } catch (failure) { if (scope.isCurrent(token)) error.value = failure }
  }
  onUnmounted(stop)
  return { job, error, active, loading, reconnecting, streamedText, start, refresh, stop, cancel }
}
