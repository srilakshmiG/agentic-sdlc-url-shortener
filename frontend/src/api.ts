// Typed client for the backend. All calls go through /api (proxied by nginx or the Vite dev server).

export type RunStatus = 'RUNNING' | 'AWAITING_HUMAN' | 'COMPLETED' | 'FAILED_ROLLED_BACK' | 'SAFE_STOPPED'
export type StageStatus =
  | 'PENDING' | 'RUNNING' | 'AWAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | 'SKIPPED'
export type FaultMode = 'TRANSIENT' | 'PRIMARY_DOWN' | 'FATAL' | 'ROGUE_ACTION'

export interface Fault { stage: string; mode: FaultMode; count: number }

export interface Run {
  id: string; title: string; requirement: string; requirementVersion: number; scenario: string; mode: string
  status: RunStatus; planVersion: number; faults: Fault[]; stopReason: string | null
  createdAt: string; finishedAt: string | null
}

export interface NodeSpec {
  id: string; title: string; agent: string; dependsOn: string[]; consumes: string[]; produces: string[]
  highImpact: boolean; maxRetries: number; fallbackAgent: string | null; lane: string
}

export interface Stage {
  runId: string; spec: NodeSpec; activeAgent: string; status: StageStatus; attempt: number; retries: number
  rejections: number; epoch: number; startedAt: string | null; finishedAt: string | null
  firstFailedAt: string | null; activeMs: number; lastError: string | null; feedback: string | null
}

export interface Question { slot: string; text: string; assumption: string }

export interface Approval {
  id: string; runId: string; stageId: string; kind: 'APPROVAL' | 'CLARIFICATION'
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'; summary: string; questions: Question[]
  answers: Record<string, string>; decidedBy: string | null; comment: string | null
  createdAt: string; decidedAt: string | null
}

export interface Decision {
  id: number; stageId: string | null; actor: string; decision: string; rationale: string
  inputs: string[]; createdAt: string
}

export interface ArtifactSummary {
  name: string; version: number; stageId: string; status: string; contentType: string
  derivedFrom: string[]; createdAt: string; bytes: number
}

export interface RunDetail {
  run: Run; stages: Stage[]; waves: string[][]; approvals: Approval[]
  decisions: Decision[]; artifacts: ArtifactSummary[]
}

export interface AuditEvent {
  seq: number; ts: string; actor: string; type: string; stageId: string | null
  payload: string; prevHash: string; hash: string
}

export interface Verification { valid: boolean; events: number; brokenAtSeq: number | null; reason: string }

export interface Scenario {
  id: string; title: string; kind: string; summary: string; requirement: string
  demonstrates: string[]; suggestedFaults: Fault[]
}

export interface Metrics {
  runsTotal: number; runsByStatus: Record<string, number>; successRate: number; stageAttempts: number
  retries: number; fallbacks: number; rollbacks: number; rollbackRate: number; replans: number
  policyViolations: number; humanApprovals: number; humanRejections: number; safeStops: number
  mttrMs: number | null; recoveredStages: number; e2eAvgMs: number | null; e2eP95Ms: number | null
  agentActiveAvgMs: number | null; humanWaitAvgMs: number | null
  stageTimings: { stage: string; avgActiveMs: number; samples: number }[]; definitions: string
}

export interface LinkItem {
  code: string; shortUrl: string; targetUrl: string; createdAt: string; expiresAt: string | null
  disabled: boolean; clickCount: number
}

export interface LinkStats {
  code: string; totalClicks: number; uniqueVisitors: number
  last7Days: { date: string; clicks: number }[]; topReferrers: { referrer: string; clicks: number }[]
  consistency: string
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!res.ok) {
    let detail = res.statusText
    try {
      const body = await res.json()
      detail = body.detail ?? body.title ?? detail
    } catch { /* non-JSON error body */ }
    throw new ApiError(res.status, detail)
  }
  if (res.status === 204 || res.status === 202) return undefined as T
  const type = res.headers.get('content-type') ?? ''
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>
}

const post = <T,>(path: string, body: unknown, headers?: Record<string, string>) =>
  call<T>(path, { method: 'POST', body: JSON.stringify(body), headers })

export const api = {
  // shortener
  createLink: (body: { url: string; alias?: string; expiresAt?: string }) =>
    post<LinkItem>('/links', body, { 'Idempotency-Key': crypto.randomUUID() }),
  links: () => call<LinkItem[]>('/links'),
  stats: (code: string) => call<LinkStats>(`/links/${encodeURIComponent(code)}/stats`),
  disable: (code: string) => call<void>(`/links/${encodeURIComponent(code)}`, { method: 'DELETE' }),
  // orchestrator
  scenarios: () => call<Scenario[]>('/orchestrator/scenarios'),
  startRun: (body: { title?: string; requirement: string; scenario?: string; faults?: Fault[]; actor?: string }) =>
    post<{ id: string }>('/orchestrator/runs', body),
  runs: () => call<Run[]>('/orchestrator/runs'),
  run: (id: string) => call<RunDetail>(`/orchestrator/runs/${id}`),
  artifact: (id: string, name: string, version?: number) =>
    call<string>(`/orchestrator/runs/${id}/artifacts/${encodeURIComponent(name)}${version ? `?version=${version}` : ''}`),
  audit: (id: string) => call<AuditEvent[]>(`/orchestrator/runs/${id}/audit`),
  verify: (id: string) => call<Verification>(`/orchestrator/runs/${id}/audit/verify`),
  decide: (id: string, approvalId: string,
           body: { decision: 'APPROVE' | 'REJECT'; by: string; comment?: string; answers?: Record<string, string> }) =>
    post<void>(`/orchestrator/runs/${id}/approvals/${approvalId}/decision`, body),
  amend: (id: string, requirement: string, by: string) =>
    post<void>(`/orchestrator/runs/${id}/requirement`, { requirement, by }),
  safeStop: (id: string, by: string, reason: string, rollback: boolean) =>
    post<void>(`/orchestrator/runs/${id}/safe-stop`, { by, reason, rollback }),
  metrics: () => call<Metrics>('/orchestrator/metrics'),
}
