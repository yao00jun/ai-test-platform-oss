export interface ScopeToken { key: string; sequence: number; signal: AbortSignal }

/** Captures screen identity and cancels superseded reads; accepted server mutations are never implicitly cancelled. */
export class RequestScope {
  private sequence = 0
  private controller?: AbortController

  begin(key: string): ScopeToken {
    this.controller?.abort()
    this.controller = new AbortController()
    return { key, sequence: ++this.sequence, signal: this.controller.signal }
  }

  isCurrent(token: ScopeToken): boolean { return token.sequence === this.sequence && !token.signal.aborted }
  invalidate(): void { this.controller?.abort(); this.sequence++ }
}
