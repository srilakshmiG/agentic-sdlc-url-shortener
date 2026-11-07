package com.agentic.orchestrator.agents;

import static com.agentic.orchestrator.agents.Agent.md;

import com.agentic.orchestrator.engine.DependencyGraph;
import com.agentic.orchestrator.engine.PlanBuilder;
import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.Model.Question;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Intake: normalize the requirement. */
@Component
class RequirementsAgent implements Agent {

    @Override public String id() { return "requirements-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        var m = ctx.model();
        var sb = new StringBuilder();
        sb.append("# Normalized requirement (v").append(ctx.requirementVersion()).append(")\n\n");
        sb.append("## Problem statement\n").append(m.title()).append("\n\n");
        sb.append("Mode: **").append(m.brownfield() ? "Brownfield (change to an existing system)" : "Greenfield (new system)").append("**\n\n");
        sb.append("## Goals\n");
        for (Feature f : m.features()) sb.append("- **").append(f.label).append("**: ").append(f.requirement).append('\n');
        if (m.features().isEmpty()) sb.append("- No recognised capability. See ambiguity report.\n");
        sb.append("\n## Acceptance criteria\n");
        for (Feature f : m.features()) {
            for (int i = 0; i < f.acceptance.size(); i++)
                sb.append("- `").append(Md.acId(f, i)).append("` ").append(f.acceptance.get(i)).append('\n');
        }
        sb.append("\n## Non-functional requirements (derived)\n");
        sb.append(Md.bullets(nfrs(m.features(), m.collectsPersonalData())));
        sb.append("\n## Out of scope\n- Production deployment (humans own releases)\n- Multi-region replication\n- Billing\n");
        sb.append("\n## Source text\n> ").append(m.text().replace("\n", "\n> ")).append('\n');
        sb.append(ctx.feedbackNote());
        return AgentResult.of("Normalized " + m.features().size() + " capabilities from the requirement",
                md("requirements.md", sb.toString()))
                .withDecisions(new DecisionDraft("Treat as " + (m.brownfield() ? "brownfield" : "greenfield"),
                        "Wording " + (m.brownfield() ? "refers to an existing system" : "describes a new system")
                                + "; features detected: " + Md.featureList(m.features())));
    }

    static List<String> nfrs(java.util.Set<Feature> features, boolean personal) {
        var l = new ArrayList<String>();
        l.add("Errors use one RFC 9457 problem-detail shape");
        l.add("Every state change is auditable");
        if (features.contains(Feature.REDIRECT)) l.add("Redirect path stays available when analytics or the broker is down");
        if (personal) l.add("No raw IP addresses are stored; visitor identity is a one-way hash; retention is bounded");
        if (features.contains(Feature.RATE_LIMIT)) l.add("Link creation is limited per client");
        return l;
    }
}

/** Finds vague wording and missing facts; pauses the run for a human when too much is open. */
@Component
class AmbiguityAgent implements Agent {

    @Override public String id() { return "ambiguity-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        var m = ctx.model();
        var sb = new StringBuilder("# Ambiguity report\n\n## Findings\n");
        if (m.vagueTerms().isEmpty() && m.openQuestions().isEmpty()) sb.append("- No vague or missing information detected.\n");
        for (String t : m.vagueTerms()) sb.append("- Vague term: \"").append(t).append("\" has no measurable meaning\n");
        for (Question q : m.openQuestions()) sb.append("- Missing: ").append(q.text()).append('\n');
        sb.append("\n## Assumptions\n");
        if (m.assumptions().isEmpty() && m.openQuestions().isEmpty()) sb.append("- none needed\n");
        m.assumptions().forEach(a -> sb.append("- ").append(a).append('\n'));
        if (m.needsClarification()) {
            sb.append("\n## Open questions (blocking)\n");
            m.openQuestions().forEach(q -> sb.append("- ").append(q.text()).append(" (default: ").append(q.assumption()).append(")\n"));
        }
        sb.append("\n## Confirmed by a human\n").append(m.answers().isEmpty() ? "- nothing yet\n"
                : m.answers().entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue() + "\n")
                .reduce("", String::concat));
        sb.append(ctx.feedbackNote());
        var result = AgentResult.of(m.needsClarification()
                ? m.openQuestions().size() + " open questions need a human answer"
                : "Ambiguity within tolerance; " + m.assumptions().size() + " assumptions recorded",
                md("ambiguity-report.md", sb.toString()));
        if (m.needsClarification()) {
            return result.withQuestions(m.openQuestions()).withDecisions(new DecisionDraft(
                    "Pause for clarification",
                    m.openQuestions().size() + " unresolved items exceed the tolerance of 1; guessing would hide risk"));
        }
        return result.withDecisions(new DecisionDraft("Proceed with recorded assumptions",
                m.openQuestions().size() + " open item(s) at or below tolerance; assumptions are written down for review"));
    }
}

/** Builds the task plan with dependencies and shows which tasks can run in parallel. */
@Component
class DecompositionAgent implements Agent {

    private final PlanBuilder planBuilder;

    DecompositionAgent(PlanBuilder planBuilder) { this.planBuilder = planBuilder; }

    @Override public String id() { return "decomposition-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        var m = ctx.model();
        var sb = new StringBuilder("# Task plan\n\n## Tasks\n\n| ID | Task | Depends on | Capability |\n|---|---|---|---|\n");
        int n = 1;
        String design = "T00";
        sb.append("| T00 | Architecture and design review (human approval) | - | all |\n");
        for (Feature f : m.features()) {
            for (String t : f.tasks) {
                sb.append(String.format("| T%02d | %s | %s | %s |%n", n, t, design, f.label));
                n++;
            }
        }
        int last = n - 1;
        sb.append(String.format("| T%02d | Unit tests for every acceptance criterion | T01-T%02d | all |%n", n++, last));
        sb.append(String.format("| T%02d | Integration tests against PostgreSQL and Kafka | T01-T%02d | all |%n", n++, last));
        sb.append(String.format("| T%02d | Documentation and runbook | T01-T%02d | all |%n", n++, last));
        sb.append(String.format("| T%02d | Release readiness review (human approval) | T%02d-T%02d | all |%n", n, n - 3, n - 1));

        var graph = planBuilder.build(m);
        sb.append("\n## Execution waves (nodes in a wave run in parallel)\n");
        var waves = DependencyGraph.waves(graph);
        for (int i = 0; i < waves.size(); i++) sb.append(i + 1).append(". ").append(String.join(", ", waves.get(i))).append('\n');
        sb.append("\n## Sequencing rules\n- Architecture and release are high impact: they need a human approval\n")
                .append("- Fan-in at compliance review waits for tests, documentation and the threat model\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of((n) + " tasks across " + waves.size() + " waves", md("task-plan.md", sb.toString()))
                .withDecisions(new DecisionDraft("Parallelise independent work",
                        "Threat model and codebase analysis share no data, tests and docs only need the implementation"));
    }
}
