package com.agentic.orchestrator.agents;

import static com.agentic.orchestrator.agents.Agent.md;

import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces the architecture document. High impact: a human approves it before anything is built. */
@Component
class ArchitectureAgent implements Agent {

    @Override public String id() { return "architecture-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        var sb = new StringBuilder("# Architecture\n\n## Context\n");
        sb.append(m.title()).append("\n\nCapabilities: ").append(Md.featureList(m.features())).append(".\n");
        sb.append("\n## Components\n");
        sb.append("- **API service** (Spring Boot): link creation, redirect, stats\n");
        sb.append("- **PostgreSQL**: source of truth for links and aggregated clicks\n");
        if (m.has(Feature.ANALYTICS)) {
            sb.append("- **Kafka** topic `link.clicks` (key = code) with a dead-letter topic: decouples redirect from analytics\n");
            sb.append("- **Click aggregator** (consumer group): idempotent writes keyed by event id\n");
        }
        sb.append("\n## Data flow\n```mermaid\nflowchart LR\n  C[Client] -->|POST /api/v1/links| A[API]\n  A --> P[(PostgreSQL)]\n  V[Visitor] -->|GET /code| A\n");
        if (m.has(Feature.ANALYTICS)) sb.append("  A -.->|async click event| K{{Kafka}}\n  K --> G[Aggregator]\n  G --> P\n");
        sb.append("```\n");
        sb.append("\n## Key decisions\n");
        sb.append("| Decision | Rationale | Trade-off |\n|---|---|---|\n");
        sb.append("| 302 redirect | Every click is observable; expiry and disable apply at once | More load than a cacheable 301 |\n");
        sb.append("| Random 7 char base-54 codes | Unguessable, no counter hot spot | Needs collision retry |\n");
        if (m.has(Feature.ANALYTICS)) {
            sb.append("| Async click events | Redirect latency and availability are independent of analytics | Counts are eventually consistent; events can be lost if the broker is down |\n");
            sb.append("| Idempotent consumer on event id | At-least-once delivery without double counting | One extra unique index |\n");
        }
        if (m.has(Feature.RATE_LIMIT)) sb.append("| In-memory fixed-window limiter | No extra infrastructure | Per instance; use Redis when scaled out |\n");
        sb.append("| Modular monolith | One deployable, clear package boundaries | Split later if teams or load demand it |\n");
        sb.append("\n## Failure modes\n");
        sb.append("| Failure | Behaviour |\n|---|---|\n");
        sb.append("| PostgreSQL down | Redirect and create fail fast with 503; health probe turns red |\n");
        if (m.has(Feature.ANALYTICS)) {
            sb.append("| Kafka down | Redirect still succeeds; event is counted as dropped and logged |\n");
            sb.append("| Poison event | Three retries then dead-letter topic; partition keeps moving |\n");
            sb.append("| Duplicate delivery | Primary key on event id ignores the second insert |\n");
        }
        sb.append("| Hot link | Single row read per redirect; add a short TTL cache if p95 requires it |\n");
        sb.append("\n## Security\n");
        sb.append("- Only http(s) targets; private, loopback and credential-bearing URLs rejected\n");
        sb.append("- Input validated on the server; errors never leak internals\n");
        sb.append("- Secrets only from environment variables\n");
        if (m.has(Feature.AUTH)) sb.append("- Ownership enforced on every link lookup to prevent IDOR\n");
        if (m.collectsPersonalData()) {
            sb.append("\n## Privacy and retention\n- Raw IP addresses are never stored; only a truncated one-way hash\n");
            sb.append("- Click data retention: ")
                    .append(m.answers().getOrDefault("retention", "90 days (purge job required)")).append("\n");
        }
        sb.append("\n## Scalability\n- Stateless API instances behind a load balancer; PostgreSQL is the first bottleneck, read replicas or a cache come next\n");
        if (m.has(Feature.ANALYTICS)) sb.append("- Kafka partitions (3) allow aggregator scale-out; ordering is per code\n");
        if (ctx.inputs().containsKey("impact-analysis.md")) {
            sb.append("\n## Brownfield impact (from codebase analysis)\n");
            ctx.input("impact-analysis.md").lines().filter(l -> l.startsWith("- `") || l.startsWith("| ")).limit(16)
                    .forEach(l -> sb.append(l).append('\n'));
        }
        sb.append("\n## Threat model reference\nSee `threat-model.md`; every threat has a mitigation listed there.\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of("Architecture covers " + m.features().size() + " capabilities with failure modes and trade-offs",
                md("architecture.md", sb.toString()))
                .withDecisions(new DecisionDraft("Modular monolith with Kafka only for analytics",
                        "Smallest design that keeps the redirect path independent of the analytics path"));
    }
}

/** One agent, four design jobs, chosen by the node it runs for. */
@Component
class DesignAgent implements Agent {

    @Override public String id() { return "design-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        return switch (ctx.spec().id()) {
            case "api-design" -> AgentResult.of("OpenAPI 3.1 contract", new ArtifactDraft("openapi.yaml", "application/yaml", openapi(ctx)));
            case "schema-migration" -> AgentResult.of("Additive, reversible migration",
                    new ArtifactDraft("schema-migration.sql", "application/sql", migration(ctx)));
            case "event-pipeline" -> AgentResult.of("Event contract and delivery semantics", md("event-pipeline.md", pipeline(ctx)));
            case "authz-design" -> AgentResult.of("Authentication and ownership rules", md("authz-design.md", authz(ctx)));
            default -> throw new IllegalStateException("design-agent cannot handle " + ctx.spec().id());
        };
    }

    private static String openapi(AgentContext ctx) {
        var m = ctx.model();
        var sb = new StringBuilder("""
                openapi: 3.1.0
                info:
                  title: URL Shortener API
                  version: 1.0.0
                paths:
                  /api/v1/links:
                    post:
                      summary: Create a short link
                      parameters:
                        - name: Idempotency-Key
                          in: header
                          schema: { type: string, maxLength: 80 }
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: { $ref: '#/components/schemas/CreateLink' }
                      responses:
                        '201': { description: Created }
                        '200': { description: Replay of an earlier idempotent request }
                        '400': { $ref: '#/components/responses/Problem' }
                        '409': { $ref: '#/components/responses/Problem' }
                        '429': { description: Rate limited, see Retry-After }
                  /{code}:
                    get:
                      summary: Redirect to the target
                      responses:
                        '302': { description: Redirect }
                        '404': { description: Unknown code }
                        '410': { description: Expired or disabled }
                """);
        if (m.has(Feature.ANALYTICS)) sb.append("  /api/v1/links/{code}/stats:\n    get:\n      summary: Click statistics (eventually consistent)\n      responses:\n        '200': { description: Totals, 7 day series, top referrers }\n");
        if (m.has(Feature.QR_CODE)) sb.append("  /api/v1/links/{code}/qr:\n    get:\n      summary: QR code PNG\n      responses:\n        '200': { description: image/png }\n");
        if (m.has(Feature.BULK)) sb.append("  /api/v1/links/bulk:\n    post:\n      summary: Create up to 100 links\n      responses:\n        '207': { description: Per-item results }\n");
        if (m.has(Feature.AUTH)) sb.append("  /api/v1/auth/token:\n    post:\n      summary: Exchange credentials for a short-lived token\n      responses:\n        '200': { description: Token }\n        '401': { description: Invalid credentials }\n");
        sb.append("components:\n  schemas:\n    CreateLink:\n      type: object\n      required: [url]\n      properties:\n        url: { type: string, maxLength: 2048 }\n");
        if (m.has(Feature.CUSTOM_ALIAS)) sb.append("        alias: { type: string, pattern: '^[A-Za-z0-9_-]{3,32}$' }\n");
        if (m.has(Feature.EXPIRY)) sb.append("        expiresAt: { type: string, format: date-time }\n");
        sb.append("  responses:\n    Problem:\n      description: RFC 9457 problem detail\n      content:\n        application/problem+json:\n          schema: { type: object }\n");
        return sb.toString() + (ctx.feedback() == null ? "" : "# Reviewer feedback addressed: " + ctx.feedback().replace("\n", " ") + "\n");
    }

    private static String migration(AgentContext ctx) {
        var m = ctx.model();
        var up = new ArrayList<String>();
        var down = new ArrayList<String>();
        if (m.has(Feature.EXPIRY)) {
            up.add("ALTER TABLE links ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;");
            down.add("ALTER TABLE links DROP COLUMN IF EXISTS expires_at;");
        }
        if (m.has(Feature.ANALYTICS)) {
            up.add("CREATE TABLE IF NOT EXISTS click_events (event_id UUID PRIMARY KEY, code VARCHAR(32) NOT NULL REFERENCES links(code), clicked_at TIMESTAMPTZ NOT NULL, referrer VARCHAR(255) NOT NULL, visitor_hash VARCHAR(64) NOT NULL);");
            up.add("CREATE INDEX IF NOT EXISTS idx_click_events_code_time ON click_events (code, clicked_at DESC);");
            down.add("DROP TABLE IF EXISTS click_events;");
        }
        if (m.has(Feature.AUTH)) {
            up.add("CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL);");
            up.add("ALTER TABLE links ADD COLUMN IF NOT EXISTS owner_id UUID NULL REFERENCES users(id);");
            down.add("ALTER TABLE links DROP COLUMN IF EXISTS owner_id;");
            down.add("DROP TABLE IF EXISTS users;");
        }
        if (m.has(Feature.MALWARE_CHECK)) {
            up.add("CREATE TABLE IF NOT EXISTS blocked_hosts (host TEXT PRIMARY KEY, reason TEXT NOT NULL);");
            down.add("DROP TABLE IF EXISTS blocked_hosts;");
        }
        if (up.isEmpty()) up.add("-- no structural change required");
        var sb = new StringBuilder("-- Expand step. Additive and nullable so the previous release keeps working.\n");
        sb.append(m.brownfield() ? "-- Brownfield: apply before deploying the new application version.\n" : "-- Greenfield: part of the initial schema.\n");
        up.forEach(s -> sb.append(s).append('\n'));
        sb.append("\n-- Rollback (run only if the release is reverted; data in new columns is lost)\n");
        down.forEach(s -> sb.append("-- ").append(s).append('\n'));
        sb.append("-- Contract step (drop old paths) is deliberately deferred to a later release.\n");
        return sb.toString();
    }

    private static String pipeline(AgentContext ctx) {
        return """
                # Click event pipeline

                ## Topology
                - Topic `link.clicks`, 3 partitions, key = short code (ordering per link)
                - Dead-letter topic `link.clicks.dlt`
                - Consumer group `click-aggregator`

                ## Event contract
                `{ eventId: uuid, code: string, clickedAt: instant, referrer: host|"direct", visitorHash: hex16 }`
                No raw IP, no full user agent.

                ## Delivery semantics
                - Producer: `acks=all`, idempotent producer, asynchronous, failure counted and logged, never surfaced to the visitor
                - Consumer: at-least-once; the insert on `event_id` is the idempotency barrier, the counter moves only when the insert happened
                - Poison messages: 3 retries 500 ms apart, then dead-letter topic

                ## Failure modes
                - Broker down: events dropped and counted (`shortener.click.publish.failed`); redirect unaffected
                - Consumer crash: offsets uncommitted, record redelivered, no double count
                - Schema change: add optional fields only
                """ + ctx.feedbackNote();
    }

    private static String authz(AgentContext ctx) {
        return """
                # Authentication and authorization

                ## Access rules
                - Creating a link requires a valid token; the owner is taken from the token, never from the request body
                - Reading, listing, disabling and stats only for the owner; foreign codes return 404 (no existence leak)

                ## Threats addressed
                - IDOR: every query filters by owner id
                - Credential stuffing: rate limit on the token endpoint
                - Token theft: short expiry, rotation

                ## Open decision
                Provider choice (own accounts vs OIDC) is a product decision for a human; this design is provider-neutral.
                """ + ctx.feedbackNote();
    }
}
