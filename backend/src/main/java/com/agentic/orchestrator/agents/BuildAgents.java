package com.agentic.orchestrator.agents;

import static com.agentic.orchestrator.agents.Agent.md;

import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces the implementation plan and reviewable code patches. Never writes to the repository itself. */
@Component
class ImplementationAgent implements Agent {

    @Override public String id() { return "implementation-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        var patches = new StringBuilder("# Proposed code patches\n\nReview-only. A human applies them through the normal pull request flow.\n");
        for (Feature f : m.features()) {
            String snippet = snippet(f);
            if (snippet != null) patches.append("\n## ").append(f.label).append("\n```java\n").append(snippet).append("\n```\n");
        }
        patches.append(ctx.feedbackNote());
        return AgentResult.of("Patches for " + m.features().size() + " capabilities",
                md("implementation-plan.md", plan(ctx, false)), md("code-patches.md", patches.toString()));
    }

    static String plan(AgentContext ctx, boolean conservative) {
        RequirementModel m = ctx.model();
        var sb = new StringBuilder("# Implementation plan\n");
        if (conservative) sb.append("\n> Produced by the conservative fallback agent: a checklist without generated patches. Human review is essential.\n");
        sb.append("\n## Work breakdown\n");
        int i = 1;
        for (Feature f : m.features()) for (String t : f.tasks) sb.append(i++).append(". ").append(t).append(" (").append(f.label).append(")\n");
        sb.append("\n## Rollout\n");
        sb.append("1. Apply the schema migration (expand step) and verify the old release still works\n");
        sb.append("2. Deploy the new version behind the existing route; keep the old version warm\n");
        sb.append("3. Watch error rate, redirect latency and `shortener.click.publish.failed` for 30 minutes\n");
        sb.append("\n## Rollback\n");
        sb.append("- Redeploy the previous version; the expand-only schema keeps it compatible\n");
        sb.append("- Run the documented down-migration only if data in new structures may be discarded\n");
        sb.append(ctx.feedbackNote());
        return sb.toString();
    }

    static String snippet(Feature f) {
        return switch (f) {
            case SHORTEN -> """
                    String target = validator.validate(req.url());   // http(s) only, no private hosts, <= 2048 chars
                    for (int i = 0; i < MAX_CODE_ATTEMPTS; i++) {
                        var link = new Link(codes.next(), target, now, req.expiresAt(), false, 0);
                        if (repo.insert(link, idempotencyKey)) return new Created(link, false);
                    }""";
            case REDIRECT -> """
                    if (link.disabled() || link.isExpired(clock.instant())) return ResponseEntity.status(GONE).build();
                    clicks.publish(code, request);   // async, failures never reach the visitor
                    return ResponseEntity.status(FOUND).location(URI.create(link.targetUrl())).build();""";
            case CUSTOM_ALIAS -> """
                    if (!codes.isValidAlias(req.alias())) throw new BadRequestException("Invalid alias");
                    if (!repo.insert(new Link(req.alias(), target, now, req.expiresAt(), false, 0), key))
                        throw new ConflictException("Alias is already taken");""";
            case EXPIRY -> """
                    if (req.expiresAt() != null && !req.expiresAt().isAfter(now))
                        throw new BadRequestException("expiresAt must be in the future");""";
            case ANALYTICS -> """
                    @KafkaListener(topics = "${app.kafka.click-topic}")
                    @Transactional
                    public void onClick(String payload) {
                        ClickEvent e = json.readValue(payload, ClickEvent.class);
                        int inserted = jdbc.sql("INSERT ... ON CONFLICT (event_id) DO NOTHING")...update();
                        if (inserted == 1) jdbc.sql("UPDATE links SET click_count = click_count + 1 WHERE code = ?")...update();
                    }""";
            case RATE_LIMIT -> """
                    if (w.count().incrementAndGet() > limit) { res.setStatus(429); res.setHeader("Retry-After", ...); return; }""";
            default -> null;
        };
    }
}

/** Lower-autonomy fallback used when the primary implementation agent keeps failing. */
@Component
class TemplateImplementationAgent implements Agent {

    @Override public String id() { return "template-implementation-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        return AgentResult.of("Conservative checklist-only implementation plan (fallback)",
                md("implementation-plan.md", ImplementationAgent.plan(ctx, true)),
                md("code-patches.md", "# Proposed code patches\n\nNo patches generated by the fallback agent.\n"
                        + "Implement the work breakdown by hand and request a fresh run when the primary agent recovers.\n"))
                .withDecisions(new DecisionDraft("Degrade to a checklist-only plan",
                        "The primary implementation agent failed repeatedly; a smaller, safer output beats no output"));
    }
}

/** Generates the unit or integration test design for the node it runs on, with acceptance traceability. */
@Component
class TestAgent implements Agent {

    @Override public String id() { return "test-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        boolean unit = ctx.spec().id().equals("unit-tests");
        RequirementModel m = ctx.model();
        var sb = new StringBuilder(unit ? "# Unit test design\n" : "# Integration test design\n");
        sb.append("\n## Test cases\n| ID | Case | Covers |\n|---|---|---|\n");
        int n = 1;
        var covered = new java.util.ArrayList<String>();
        for (Feature f : m.features()) {
            boolean integrationOnly = f == Feature.ANALYTICS || f == Feature.RELIABILITY || f == Feature.REDIRECT;
            if (unit == integrationOnly) continue;
            for (int i = 0; i < f.tests.size(); i++) {
                // case i covers acceptance criterion i; the last case also covers any remaining criteria
                var ids = new java.util.ArrayList<String>();
                int end = i == f.tests.size() - 1 ? f.acceptance.size() : Math.min(i + 1, f.acceptance.size());
                for (int a = Math.min(i, f.acceptance.size() - 1); a < Math.max(end, 1); a++) ids.add(Md.acId(f, a));
                covered.addAll(ids);
                sb.append("| ").append(unit ? "UT-" : "IT-").append(n++).append(" | ").append(f.tests.get(i))
                        .append(" | ").append(String.join(", ", ids)).append(" |\n");
            }
        }
        if (!unit) {
            sb.append("| IT-X1 | broker unavailable: redirect still returns 302 | resilience |\n");
            sb.append("| IT-X2 | schema migrations apply on an empty database | all |\n");
        }
        sb.append("\n## Traceability\n");
        for (String id : covered.stream().distinct().toList())
            sb.append("- ").append(id).append(" -> ").append(unit ? "unit" : "integration").append(" test\n");
        sb.append("\n## Environment\n").append(unit ? "- Pure JVM, no containers\n"
                : "- Real PostgreSQL and Kafka from Docker Compose; no mocks of infrastructure\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of((n - 1) + " cases mapped to acceptance criteria",
                md(unit ? "unit-tests.md" : "integration-tests.md", sb.toString()));
    }
}

/** Documentation and runbook generated from the API design and the chosen features. */
@Component
class DocsAgent implements Agent {

    @Override public String id() { return "docs-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        var sb = new StringBuilder("# Documentation\n\n## Usage\n```bash\ncurl -X POST $BASE/api/v1/links -H 'Content-Type: application/json' \\\n  -d '{\"url\":\"https://example.com/a/very/long/path\"");
        if (m.has(Feature.CUSTOM_ALIAS)) sb.append(",\"alias\":\"my-link\"");
        if (m.has(Feature.EXPIRY)) sb.append(",\"expiresAt\":\"2030-01-01T00:00:00Z\"");
        sb.append("}'\n```\n");
        sb.append("\n## Endpoints\n").append(Md.bullets(m.features().stream().flatMap(f -> f.endpoints.stream()).distinct().toList()));
        sb.append("\n## Configuration\n- `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `PUBLIC_BASE_URL`\n");
        sb.append("\n## Runbook\n");
        sb.append("- Health: `/actuator/health`. Red means PostgreSQL or Kafka is unreachable\n");
        if (m.has(Feature.ANALYTICS)) sb.append("- Dead-letter topic `link.clicks.dlt` growing: inspect payloads, fix the cause, replay\n");
        sb.append("- 429 spikes: confirm legitimate traffic, then raise `CREATES_PER_MINUTE` or block the client\n");
        sb.append("- Rollback: redeploy the previous image; migrations are additive\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of("Usage, configuration and runbook", md("documentation.md", sb.toString()));
    }
}
