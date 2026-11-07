# Agentic SDLC Shortener

A URL shortener (Java, Spring Boot, PostgreSQL, Kafka) plus an **agentic SDLC orchestration layer** that turns a plain-language requirement into reviewable engineering artifacts under governance. React UI. Everything runs in Docker; nothing is installed on your machine.

| Part | What it is |
|---|---|
| `backend/` | Java 25, Spring Boot 4.1, JdbcClient, Flyway, Spring Kafka. Packages: `shortener`, `analytics`, `orchestrator` |
| `frontend/` | React 19, TypeScript, Vite, served by nginx. Light, dark and system themes |
| PostgreSQL 18 | Links, clicks, and all orchestration state (runs, stages, artifacts, approvals, hash-chained audit) |
| Kafka 4.1 (KRaft) | Click events (`link.clicks`) with a dead-letter topic (`link.clicks.dlt`) |

See [architecture.md](architecture.md) for the diagrams and design decisions, and [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) for the assessment write-up (scenarios, risks, trade-offs, limitations).

## Set up

Only Docker (with Compose v2) is required.

```bash
docker compose up -d --build
```

First build downloads dependencies and takes a few minutes. When it finishes:

| URL | What |
|---|---|
| http://localhost:8481 | The UI |
| http://localhost:8480 | The API (short links resolve here, e.g. `http://localhost:8480/abc1234`) |
| http://localhost:8480/actuator/health | Health |

Stop with `docker compose down` (add `-v` to wipe the database).

Ports 8480/8481 are chosen to avoid common clashes. Change them in `docker-compose.yml`; if you change the API port also set `PUBLIC_BASE_URL`.

### Try it in two minutes

1. Open http://localhost:8481 and choose **Agentic SDLC**.
2. Click **Run this scenario** on *Greenfield*. The run page shows the dependency graph filling in. Parallel branches run side by side.
3. When a yellow **Approval needed** card appears (architecture, then release), read the artifact with the *View* button and **Approve**. Try **Reject with feedback** once: the agent regenerates the artifact with your comment.
4. Open the **Audit trail** tab and press **Verify hash chain**.
5. Run *Brownfield* (reads this repository's real source) and *Ambiguous* (pauses with prefilled questions; confirm them and watch the graph re-plan).
6. Open **Reliability** for success rate, retries, rollbacks, MTTR and latency.
7. Under **URL shortener**, create a link, open it a few times, and watch the click count arrive through Kafka.

## Tests

```bash
# unit + integration tests (real PostgreSQL and Kafka from Compose), 74 tests
docker compose --profile test run --rm --build tests mvn -B test

# end-to-end smoke of the running stack: shortener, Kafka analytics, three scenarios, guardrails
powershell -File scripts/smoke.ps1        # Windows PowerShell; any pwsh also works
```

Integration tests skip themselves unless `IT_DB_URL` is set, which only the `tests` service does.

## API quick reference

```bash
# shorten (Idempotency-Key makes retries safe)
curl -X POST localhost:8480/api/v1/links -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f1c' -d '{"url":"https://example.com/long","alias":"my-link","expiresAt":"2030-01-01T00:00:00Z"}'
curl -i localhost:8480/my-link                 # 302, 404 unknown, 410 expired or disabled
curl localhost:8480/api/v1/links/my-link/stats # eventually consistent

# orchestrator
curl localhost:8480/api/v1/orchestrator/scenarios
curl -X POST localhost:8480/api/v1/orchestrator/runs -H 'Content-Type: application/json' \
  -d '{"requirement":"Add QR codes to every short link.","scenario":"BROWNFIELD"}'
# GET /runs/{id}, /runs/{id}/artifacts/{name}, /runs/{id}/audit, /runs/{id}/audit/verify
# POST /runs/{id}/approvals/{approvalId}/decision  {"decision":"APPROVE|REJECT","by":"me","comment":"..."}
# POST /runs/{id}/requirement {"requirement":"..."}   (amend and re-plan)
# POST /runs/{id}/safe-stop   {"by":"me","reason":"...","rollback":true}
# GET  /metrics
```

Errors use RFC 9457 problem details everywhere.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_PASSWORD` | `shortener` | Database password (set a real one outside local use) |
| `PUBLIC_BASE_URL` | `http://localhost:8480` | Base of generated short URLs |
| `CREATES_PER_MINUTE` | `60` | Link creation rate limit per client |
| `TRUST_PROXY` | `true` in Compose | Trust `X-Forwarded-For` (only safe behind your own proxy) |
| `STAGE_DELAY_MS` | `600` | Simulated agent thinking time so the UI is watchable; `0` for tests |
| `RETRY_BACKOFF_MS` | `300` | Base of exponential retry backoff |

## Honest limitations

- Agents are **deterministic**, rule-based templates over a feature catalogue. They implement an `Agent` interface an LLM-backed agent could fill, under the same guardrails, but no LLM is called. Outputs are designs, patches and tests for review; nothing is written to a repository or deployed.
- Operator identity in the orchestrator is a free-text name, and the API has no authentication. Do not expose it publicly.
- The rate limiter is in memory (per instance). Click analytics are eventually consistent and best effort if Kafka is down.
- The UI does not use the Arc design system; it uses hand-written, token-based CSS with light and dark themes. I could not verify the UI visually because the browser extension timed out during this build, so it is verified only by a successful typed build and API calls.
