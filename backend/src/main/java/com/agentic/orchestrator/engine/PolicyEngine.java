package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.agents.Agent;
import com.agentic.orchestrator.agents.Agent.ArtifactDraft;
import com.agentic.orchestrator.agents.Agent.AgentResult;
import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.Model.Severity;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Guardrails for security, compliance and change control. HARD violations safe-stop the run;
 * SOFT violations fail the stage so it is retried with the violation as feedback.
 */
@Component
public class PolicyEngine {

    public record Violation(String rule, String category, Severity severity, String message) {
        public String describe() { return rule + " (" + category + "): " + message; }
    }

    private static final Pattern SECRET = Pattern.compile(
            "(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?i:password|secret|api[_-]?key)\\s*[:=]\\s*['\"]?[A-Za-z0-9/+_-]{8,})");

    /** Exit gate: sections or markers every artifact of that name must contain. */
    static final Map<String, List<String>> REQUIRED = Map.ofEntries(
            Map.entry("requirements.md", List.of("## Acceptance criteria")),
            Map.entry("ambiguity-report.md", List.of("## Assumptions")),
            Map.entry("task-plan.md", List.of("## Tasks")),
            Map.entry("impact-analysis.md", List.of("## Impacted files")),
            Map.entry("threat-model.md", List.of("## Threats")),
            Map.entry("architecture.md", List.of("## Failure modes", "## Security")),
            Map.entry("openapi.yaml", List.of("openapi: 3.1.0")),
            Map.entry("schema-migration.sql", List.of("-- Rollback")),
            Map.entry("event-pipeline.md", List.of("## Delivery semantics")),
            Map.entry("authz-design.md", List.of("## Access rules")),
            Map.entry("implementation-plan.md", List.of("## Rollout", "## Rollback")),
            Map.entry("unit-tests.md", List.of("## Traceability")),
            Map.entry("integration-tests.md", List.of("## Traceability")),
            Map.entry("documentation.md", List.of("## Runbook")),
            Map.entry("compliance-report.md", List.of("## Policy results")),
            Map.entry("release-checklist.md", List.of("## Rollback plan")),
            Map.entry("engineering-summary.md", List.of("## Assumptions", "## Limitations", "## Risks and trade-offs")));

    /** Intake guardrail: requirements that ask to weaken controls are blocked before any agent runs. */
    public List<Violation> checkRequirement(RequirementModel m) {
        var v = new ArrayList<Violation>();
        for (String ask : m.forbiddenAsks())
            v.add(new Violation("CHG-003", "change-control", Severity.HARD,
                    "Requirement asks to weaken a control (\"" + ask + "\"). Agents may not do this; a human must change the policy."));
        return v;
    }

    /** Checks an agent result: autonomy boundary, secrets, exit gate, privacy. */
    public List<Violation> checkResult(NodeSpec spec, Agent agent, AgentResult result, RequirementModel m) {
        var v = new ArrayList<Violation>();
        for (String action : result.actions()) {
            boolean allowed = agent.capabilities().stream().anyMatch(c -> c.name().equals(action));
            if (!allowed) v.add(new Violation("CHG-002", "change-control", Severity.HARD,
                    "Agent " + agent.id() + " attempted action " + action + " outside its autonomy boundary"));
        }
        for (ArtifactDraft a : result.artifacts()) {
            if (SECRET.matcher(a.content()).find())
                v.add(new Violation("SEC-001", "security", Severity.HARD, "Possible secret in " + a.name()));
        }
        for (String expected : spec.produces()) {
            ArtifactDraft draft = result.artifacts().stream().filter(a -> a.name().equals(expected)).findFirst().orElse(null);
            if (draft == null) {
                v.add(new Violation("GATE-001", "exit-gate", Severity.SOFT, "Missing declared output " + expected));
                continue;
            }
            if (draft.content().isBlank())
                v.add(new Violation("GATE-002", "exit-gate", Severity.SOFT, expected + " is empty"));
            for (String marker : REQUIRED.getOrDefault(expected, List.of()))
                if (!draft.content().contains(marker))
                    v.add(new Violation("GATE-003", "exit-gate", Severity.SOFT, expected + " lacks required section \"" + marker + "\""));
            if (expected.equals("compliance-report.md") && draft.content().contains("RESULT: FAIL"))
                v.add(new Violation("GATE-004", "exit-gate", Severity.SOFT, "Compliance report failed: fix upstream artifacts"));
            if (expected.equals("architecture.md") && m.collectsPersonalData() && !draft.content().toLowerCase().contains("retention"))
                v.add(new Violation("COMP-001", "compliance", Severity.SOFT, "Personal data is collected but no retention is stated"));
            if (expected.equals("architecture.md") && m.has(Feature.SHORTEN) && !draft.content().contains("private, loopback"))
                v.add(new Violation("SEC-004", "security", Severity.SOFT, "Design does not address unsafe redirect targets"));
        }
        return v;
    }

    public static boolean hasHard(List<Violation> v) {
        return v.stream().anyMatch(x -> x.severity() == Severity.HARD);
    }

    public static String feedback(List<Violation> v) {
        return v.stream().map(x -> "- " + x.describe()).reduce("", (a, b) -> a + b + "\n").strip();
    }
}
