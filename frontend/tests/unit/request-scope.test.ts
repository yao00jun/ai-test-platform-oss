import { describe, expect, it } from 'vitest'
import { RequestScope } from '../../src/core/request-scope'
import { JobStream, type EventSourcePort } from '../../src/api/job-stream'

class Source extends EventTarget implements EventSourcePort {
  onerror: ((event: Event) => void) | null = null
  close() { /* A late buffered event remains possible after close in a real browser. */ }
  emit(type: string, payload: unknown) { this.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(payload) })) }
}

describe('request and event isolation', () => {
  it('rejects old responses after switching project and aborts their request', () => {
    const scope = new RequestScope()
    const first = scope.begin('project-a:case-1')
    const second = scope.begin('project-b:case-1')
    expect(first.signal.aborted).toBe(true)
    expect(scope.isCurrent(first)).toBe(false)
    expect(scope.isCurrent(second)).toBe(true)
    scope.invalidate()
    expect(scope.isCurrent(second)).toBe(false)
  })

  it('ignores events from replaced subscriptions, wrong jobs, and wrong projects', () => {
    const sources: Source[] = []
    const events: string[] = []
    const stream = new JobStream(() => { const source = new Source(); sources.push(source); return source })
    stream.subscribe({ projectId: 'p1', jobId: 'j1' }, (event) => events.push(String(event.data.value)))
    sources[0]!.emit('token', { projectId: 'p1', jobId: 'j1', value: 'first' })
    stream.subscribe({ projectId: 'p2', jobId: 'j2' }, (event) => events.push(String(event.data.value)))
    sources[0]!.emit('done', { projectId: 'p1', jobId: 'j1', value: 'stale' })
    sources[1]!.emit('token', { projectId: 'p1', jobId: 'j2', value: 'wrong-project' })
    sources[1]!.emit('token', { projectId: 'p2', jobId: 'j1', value: 'wrong-job' })
    sources[1]!.emit('token', { projectId: 'p2', jobId: 'j2', value: 'second' })
    stream.close()
    sources[1]!.emit('done', { projectId: 'p2', jobId: 'j2', value: 'closed' })
    expect(events).toEqual(['first', 'second'])
  })

  it('isolates malformed SSE payloads while accepting valid events on the bound job URL', () => {
    const source = new Source()
    const events: unknown[] = []
    const stream = new JobStream(() => source)
    stream.subscribe({ projectId: 'p1', jobId: 'j1' }, (event) => events.push(event.data))
    source.dispatchEvent(new MessageEvent('token', { data: 'not-json' }))
    source.emit('progress', { progress: 0.25, message: '执行中' })
    expect(events).toEqual([{ progress: 0.25, message: '执行中' }])
    stream.close()
  })
})
