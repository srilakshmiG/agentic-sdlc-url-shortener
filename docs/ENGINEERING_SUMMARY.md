# Engineering summary

## Plan and rationale

The assessment asks for a working agentic SDLC system with a URL shortener as the subject. I built two things that meet at the database:

1. **The product**: a URL shortener with create (custom alias, expiry, idempotency key, rate limit, unsafe-target validation), redirect (302/404/410), and Kafka-based click analytics with an idempotent consumer and a dead-letter topic.
2. **The orchestrator**: a stateful engine that takes a requirement through intake, ambiguity review, decomposition, threat model, optional codebase analysis, architecture, API/schema/event/auth design, implementation, tests, documentation, a compliance gate, release readiness and a final summary.

Sequence of work: product core first (so the brownfield scenario has real code to analyse), then the engine and policy/audit/metrics, then the agents, UI, tests, and finally Docker packaging and verification.

## Requirement-by-requirement

| Requirement | Where it is met |
|---|---|
| Requirement understanding | `RequirementAnalyzer`: features, brownfield detection, vague terms, missing facts, forbidden asks |
| Task decomposition | `DecompositionAgent` task table with dependencies and execution waves |
| Codebase reasoning | `CodebaseAgent` scans the real source: capability present or absent, impacted files and modules, API surface, tables, topics, compatibility |
| Orchestration | `WorkflowEngine`: explicit graph, entry/exit gates, parallel branches and fan-in, durable context and lineage, approvals, bounded retries, fallback, rollback, safe-stop, guardrails, audit, metrics, re-plan |
| Output generation | Requirements, task plan, threat model, architecture, OpenAPI 3.1, migration with rollback, event contract, code patches, test design with traceability, docs and runbook, compliance report, release checklist |
| Validation and risk | Policy engine, exit gates, compliance traceability check, risk register, 74 automated tests |
| Controlled autonomy | Agents can only write artifacts; high-impact stages need a human; every decision recorded |

## The three scenarios

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Input | Build the shortener from scratch with aliases, expiry, Kafka analytics, rate limiting | Add expiry and aliases to the existing code, keep API compatible | "Make our short links smarter and faster, and keep everything secure." |
| Decomposition | 15 nodes, no codebase analysis | 16 nodes incl. codebase-analysis in parallel with the threat model | Pauses after ambiguity review |
| Orchestration | Injected transient fault: implementation retries twice then succeeds; approvals on architecture and release | Injected primary-agent outage: fallback agent engaged; schema migration is a gated high-impact node | Three questions with proposed defaults; answering rewrites the requirement, re-plans (plan v2, new `event-pipeline` node) |
| Validation | Compliance gate PASS, audit chain intact | Impact analysis found the capabilities already present in `LinkService` / `RedirectController` and recommends extending and regression-testing | Nothing downstream ran on a guess |

Failure paths are demonstrated with fault injection: permanent failure (rollback in reverse completion order, artifacts revoked), a rogue `DEPLOY_PRODUCTION` action (hard policy violation, safe-stop, output not committed), and a requirement that asks to disable authentication (blocked at intake before any agent runs).

## Artifacts

All produced per run, versioned, with derived-from lineage, viewable in the UI and via `GET /runs/{id}/artifacts/{name}`.

## Risks and trade-offs

- Deterministic agents are reproducible and safe but limited to the feature catalogue. Real LLM agents add capability and non-determinism; the interface, gates and policy engine are where that risk would be contained.
- Per-run lock keeps reasoning simple; it caps parallel throughput inside one run.
- Early cutoff depends on stable agent output; a non-deterministic agent would defeat it unless normalised.
- Click analytics are best effort and eventually consistent by design; redirect availability was prioritised.
- The in-memory rate limiter is per instance.
- The clarification threshold (two open questions) and the keyword-based analyzer are heuristics and will misjudge some phrasing.

## Validation performed

- 74 automated tests pass: 51 unit (URL validation, code generation, analyzer, graph, planner for 100+ feature combinations, policy, audit tamper detection, all agents through full plans against the exit gates) and 23 integration against real PostgreSQL and Kafka (engine scenarios, retries, fallback, rollback, safe-stop, re-plan, early cutoff, rejection feedback, audit tampering, metrics; shortener over HTTP, duplicate Kafka delivery, dead-letter routing, expiry, idempotency, rate limit).
- `scripts/smoke.ps1` passes against the running Compose stack (all three scenarios plus guardrails).
- The typed React build succeeds and nginx serves it and proxies the API.

## Assumptions

- Operators are trusted; identity is a name string.
- One database, one API instance.
- Click data retention of 90 days is stated in design, but no purge job is implemented.

## Limitations

- The UI was **not visually inspected**: the browser extension timed out. Layout, theming and responsiveness are untested by eye or by automated browser tests.
- No authentication on the API; do not expose publicly.
- Generated patches and tests are designs for human review; the system does not write to a repository, run generated code, or deploy.
- No purge job for click data; no load or performance testing was done.
- The Arc design system was not used for the UI.
