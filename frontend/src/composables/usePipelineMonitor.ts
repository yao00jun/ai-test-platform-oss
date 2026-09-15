import { computed, onUnmounted, ref, watch } from 'vue'
import { activePipelineStates, pipelineApi, type Pipeline } from '../api/pipelines'
import { ApiError } from '../api/client'
import { RequestScope, type ScopeToken } from '../core/request-scope'
import { useJobMonitor } from './useJobMonitor'

/** The pipeline record owns progress; a terminal stage job is only one checkpoint. */
export function usePipelineMonitor(onChange: (pipeline: Pipeline) => void) {
  const pipeline = ref<Pipeline>(), error = ref<unknown>(), loading = ref(false)
  const job = useJobMonitor(), scope = new RequestScope()
  const active = computed(() => !!pipeline.value && activePipelineStates.has(pipeline.value.status))
  let current: ScopeToken | undefined, project = '', id = '', jobId = '', observed = '', failures = 0, reading = false
  let timer: ReturnType<typeof setTimeout> | undefined
  function stop() {
    scope.invalidate(); job.stop(); clearTimeout(timer); current = undefined; reading = false; loading.value = false; pipeline.value = undefined; error.value = undefined; jobId = ''; observed = ''; failures = 0
  }
  async function refresh() {
    const token = current
    if (!token || reading) return
    reading = true; loading.value = true; clearTimeout(timer)
    let permanentFailure = false
    try {
      const value = await pipelineApi.get(project, id, token.signal)
      if (!scope.isCurrent(token)) return
      pipeline.value = value; error.value = undefined; failures = 0
      const signature = `${value.id}:${value.revision}:${value.status}:${value.assetIds.join(',')}:${JSON.stringify(value.execution)}`
      if (observed && observed !== signature) onChange(value)
      observed = signature
      if (value.jobId && jobId !== value.jobId) { jobId = value.jobId; void job.start(project, value.jobId) }
    } catch (failure) {
      if (!scope.isCurrent(token)) return
      error.value = failure; failures++
      permanentFailure = failure instanceof ApiError && [403, 404].includes(failure.status)
    } finally {
      if (scope.isCurrent(token)) {
        reading = false; loading.value = false
        if (!permanentFailure && (!pipeline.value || active.value)) timer = setTimeout(() => { void refresh() }, Math.min(1500 * 2 ** Math.min(failures, 4), 10000))
      }
    }
  }
  async function start(projectId: string, pipelineId: string) { stop(); project = projectId; id = pipelineId; current = scope.begin(`${project}:${id}`); await refresh() }
  watch(() => [job.job.value?.id, job.job.value?.status, job.job.value?.progress], () => { if (current && job.job.value?.id === jobId) void refresh() })
  onUnmounted(stop)
  return { pipeline, error, loading, active, job, start, stop, refresh }
}
