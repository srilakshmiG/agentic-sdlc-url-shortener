# Architecture

Diagrams are Mermaid and render on GitHub and most editors.

## 1. System context

```mermaid
flowchart LR
  H[Human reviewer] -->|approve, reject, clarify, amend, safe-stop| UI[React UI via nginx :8481]
  V[Visitor] -->|GET /code| API
  UI -->|/api/v1| API[Spring Boot API :8480]
  API --> PG[(PostgreSQL)]
  API -. async click event .-> K{{Kafka link.clicks}}
  K --> AGG[Click aggregator, same service]
  AGG --> PG
  K -. poison after 3 retries .-> DLT{{link.clicks.dlt}}
  API --- ORCH[Orchestrator: engine, agents, policy, audit]
  ORCH --> PG
  ORCH -. read-only scan .-> SRC[(Source tree copy)]
```

One deployable (modular monolith) with three packages: `shortener`, `analytics`, `orchestrator`. The redirect path and the analytics path are decoupled by Kafka; the orchestrator is tooling and shares only the database.

## 2. Redirect and click analytics

```mermaid
sequenceDiagram
  participant V as Visitor
  participant A as API
  participant DB as PostgreSQL
  participant K as Kafka
  participant C as Aggregator
  V->>A: GET /abc1234
  A->>DB: find link
  alt unknown
    A-->>V: 404
  else disabled or expired
    A-->>V: 410
  else active
    A-)K: publish event (virtual thread, failures counted, never surfaced)
    A-->>V: 302 Location
    K->>C: deliver (at least once)
    C->>DB: INSERT click_events ON CONFLICT DO NOTHING
    opt inserted
      C->>DB: click_count + 1
    end
    Note over C,K: failure: 3 retries 500 ms apart, then link.clicks.dlt
  end
```

The event id primary key is the idempotency barrier, so redelivery cannot double count. Raw IPs never leave the request: only a truncated one-way hash is carried.

## 3. Orchestration model

A requirement becomes an explicit dependency graph. Nodes run when all dependencies have succeeded, so independent branches run in parallel and fan-in nodes synchronize. The graph depends on the requirement: extra capabilities add nodes, brownfield adds codebase analysis.

```mermaid
flowchart LR
  intake --> ambiguity[ambiguity-review]
  ambiguity --> decompose
  decompose --> threat[threat-model]
  decompose --> code[codebase-analysis<br/>brownfield only]
  threat --> arch{{architecture<br/>HUMAN APPROVAL}}
  code --> arch
  arch --> api[api-design]
  arch --> schema[schema-migration<br/>approval if brownfield]
  arch --> events[event-pipeline<br/>if analytics]
  arch --> authz[authz-design<br/>if auth]
  api --> impl[implementation<br/>fallback agent]
  schema --> impl
  events --> impl
  authz --> impl
  impl --> ut[unit-tests]
  impl --> it[integration-tests]
  impl --> docs[documentation]
  ut --> comp[compliance-review<br/>fan-in gate]
  it --> comp
  docs --> comp
  threat --> comp
  comp --> rel{{release-readiness<br/>HUMAN APPROVAL}}
  rel --> sum[engineering-summary]
```

Each node declares what it **consumes** and **produces**. Entry gate: every consumed artifact must be active. Exit gate: every produced artifact must exist, be non-empty and contain its required sections; the policy engine also checks the result.

### Stage lifecycle

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> RUNNING: deps done + entry gate
  RUNNING --> SUCCEEDED: exit gate + policy pass
  RUNNING --> AWAITING_APPROVAL: high impact or open questions
  AWAITING_APPROVAL --> SUCCEEDED: human approves
  AWAITING_APPROVAL --> PENDING: human rejects (feedback) or inputs change
  RUNNING --> RUNNING: soft failure, bounded retry with backoff
  RUNNING --> RUNNING: retries exhausted, fallback agent engaged
  RUNNING --> FAILED: retries and fallback exhausted
  SUCCEEDED --> PENDING: upstream artifact changed (re-plan)
  SUCCEEDED --> ROLLED_BACK: run rolled back
  PENDING --> SKIPPED: run stopped or node removed
  FAILED --> [*]
  ROLLED_BACK --> [*]
```

### Run lifecycle

```mermaid
stateDiagram-v2
  [*] --> RUNNING
  RUNNING --> AWAITING_HUMAN: nothing runnable, approvals pending
  AWAITING_HUMAN --> RUNNING: decision made
  RUNNING --> COMPLETED: all stages done
  RUNNING --> FAILED_ROLLED_BACK: stage exhausted, compensate in reverse order
  RUNNING --> SAFE_STOPPED: human stop, hard policy violation, repeated rejection, deadlock
  AWAITING_HUMAN --> SAFE_STOPPED
```

## 4. Control flow of one stage

```mermaid
sequenceDiagram
  participant E as Engine (per-run lock)
  participant S as Agent (virtual thread)
  participant P as Policy engine
  participant DB as PostgreSQL
  E->>DB: mark RUNNING, audit GATE_ENTRY_PASSED, STAGE_STARTED
  E-)S: execute with inputs, epoch captured
  S-->>E: result or exception
  E->>E: epoch still current? else discard (plan changed)
  E->>P: check autonomy boundary, secrets, exit gate
  alt hard violation
    E->>DB: audit POLICY_VIOLATION, SAFE_STOP
  else soft violation or exception
    E->>DB: audit STAGE_FAILED, retry / fallback / rollback
  else ok
    E->>DB: versioned artifacts with derived-from lineage, decisions
    alt needs human
      E->>DB: create approval, AWAITING_APPROVAL
    else
      E->>DB: SUCCEEDED, audit
    end
  end
  E->>E: advance: dispatch newly ready stages
```

Agent work runs outside the lock; state transitions run inside it and inside one database transaction. Because state is durable, a restart re-queues in-flight stages (`RECOVERED` event).

## 5. Re-planning on change

```mermaid
flowchart TD
  A[Requirement amended<br/>or clarification answered] --> B[Re-analyse text, rebuild graph]
  B --> C[Diff against live graph]
  C --> D[Add new nodes]
  C --> E[Skip removed nodes, revoke artifacts]
  C --> F[Reset nodes whose definition changed]
  B --> G[Reset intake]
  G --> H[Intake re-runs]
  H --> I{requirements.md changed?}
  I -- no --> Z[Done: early cutoff]
  I -- yes --> J[Invalidate only stages that consume it]
  J --> K[Each re-runs]
  K --> L{its output changed?}
  L -- no --> Z
  L -- yes --> J
```

Early cutoff (as in build systems) is what keeps re-plans cheap: a stage whose regenerated output is byte-identical keeps its dependents, and an approved design that does not change is not sent back for approval (`APPROVAL_REUSED`). A changed design always needs a new approval.

## 6. Governance and observability

```mermaid
flowchart LR
  subgraph Guardrails
    G1[Intake: requirement must not weaken controls] 
    G2[Autonomy boundary: agents may only write artifacts]
    G3[Secret scan on every artifact]
    G4[Exit gates: required sections, compliance result]
    G5[Change control: high-impact stages cannot skip a human]
  end
  subgraph Evidence
    A1[Hash-chained audit log]
    A2[Decision lineage with rationale]
    A3[Artifact versions with derived-from inputs]
    A4[Metrics from audit and stage tables]
  end
  Guardrails --> Evidence
```

| Guardrail | Rule | Severity | Effect |
|---|---|---|---|
| CHG-003 | Requirement asks to disable auth, skip review, auto-deploy | HARD | Run safe-stopped before any agent runs |
| CHG-002 | Agent action outside declared capabilities (for example `DEPLOY_PRODUCTION`) | HARD | Safe-stop, output not committed |
| SEC-001 | Secret-like text in an artifact | HARD | Safe-stop |
| GATE-001..004 | Missing output, empty, missing section, failed compliance report | SOFT | Stage fails, retried with feedback |
| COMP-001, SEC-004 | Personal data without retention; no unsafe-target handling in design | SOFT | Stage fails, retried |
| CHG-001 | High-impact node needs human approval | structural | Enforced by the engine, cannot be configured away by an agent |

**Audit chain.** Each event stores `hash = SHA-256(prev_hash | run | time | actor | type | stage | payload)`. Editing, removing or reordering any event breaks verification from that point (`GET /runs/{id}/audit/verify`, tested by altering a row).

**Metrics** (`GET /metrics`): success rate (completed over finished runs), retries, fallbacks, rollback count and rate, re-plans, policy violations, human decisions, **MTTR** (first failure to eventual success of the same stage), **end-to-end latency** average and p95, agent-active versus human-wait time, and per-stage timings.

## 7. Data model

```mermaid
erDiagram
  RUNS ||--o{ STAGES : has
  RUNS ||--o{ ARTIFACTS : produces
  RUNS ||--o{ APPROVALS : requests
  RUNS ||--o{ DECISIONS : records
  RUNS ||--o{ AUDIT_EVENTS : chains
  LINKS ||--o{ CLICK_EVENTS : receives
  RUNS { uuid id PK
    text requirement
    int requirement_version
    int plan_version
    string status }
  STAGES { string node_id PK
    json spec
    string status
    int epoch
    int attempt }
  ARTIFACTS { string name
    int version
    string content_hash
    json derived_from
    string status }
  AUDIT_EVENTS { bigint seq PK
    string prev_hash
    string hash }
  LINKS { string code PK
    text target_url
    timestamptz expires_at
    bigint click_count }
  CLICK_EVENTS { uuid event_id PK
    string code FK
    string visitor_hash }
```

## 8. Key decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Modular monolith | One deployable, fastest to run and review | Orchestrator and product share a process and database |
| State in PostgreSQL, not memory | Survives restarts, powers audit and metrics, enables recovery | More writes; a per-run lock serializes transitions |
| Per-run lock + epoch counter | Simple correctness for parallel stages and re-plans: stale results are discarded | Throughput per run limited to the lock, fine for tens of stages |
| Deterministic agents behind an interface | Reproducible, testable, no secrets or network; guardrails apply the same to any future LLM agent | Outputs are templated, not creative; limited to catalogued capabilities |
| Early cutoff on artifact hashes | Re-plans rebuild only what actually changed | Needs byte-stable agent output |
| Soft vs hard policy | Fixable gaps retry; boundary breaches stop the run | Thresholds (such as clarification at 2 open questions) are judgement calls |
| 302 redirect | Clicks observable, expiry immediate | More load than cacheable 301 |
| Best-effort click publishing | Redirect availability beats analytics completeness | Events lost while the broker is down (counted in `shortener.click.publish.failed`) |
| In-memory rate limiter | No extra infrastructure | Per instance; move to Redis when scaled out |
