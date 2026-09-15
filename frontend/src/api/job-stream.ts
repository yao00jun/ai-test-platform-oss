import { isRecord, queryString } from './client'

export interface EventSourcePort {
  addEventListener(type: string, listener: EventListener): void
  onerror: ((event: Event) => void) | null
  close(): void
}
export interface JobEvent { type: string; data: Record<string, unknown>; lastEventId: string }
export interface JobScope { projectId: string; jobId: string }
const eventTypes = ['progress', 'token', 'asset', 'result', 'error', 'done']

/** EventSource owns reconnect/Last-Event-ID; the generation guard also rejects buffered old events after close. */
export class JobStream {
  private source?: EventSourcePort
  private generation = 0

  constructor(private readonly createSource: (url: string) => EventSourcePort = (url) => new EventSource(url)) {}

  subscribe(scope: JobScope, onEvent: (event: JobEvent) => void, onDisconnect?: () => void): void {
    this.close()
    const generation = this.generation
    const source = this.createSource(`/api/jobs/${encodeURIComponent(scope.jobId)}/events${queryString({ projectId: scope.projectId })}`)
    this.source = source
    for (const type of eventTypes) {
      source.addEventListener(type, (event) => {
        if (generation !== this.generation || !('data' in event)) return
        let data: unknown
        try { data = JSON.parse(String((event as MessageEvent).data)) } catch { return }
        if (!isRecord(data)) return
        if ((data.jobId !== undefined && data.jobId !== scope.jobId) || (data.projectId !== undefined && data.projectId !== scope.projectId)) return
        onEvent({ type, data, lastEventId: (event as MessageEvent).lastEventId || '' })
      })
    }
    source.onerror = () => { if (generation === this.generation) onDisconnect?.() }
  }

  close(): void { this.generation++; this.source?.close(); this.source = undefined }
}
