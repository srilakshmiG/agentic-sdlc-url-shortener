package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a requirement into an explicit dependency graph. The graph depends on what the requirement
 * asks for, so a changed requirement yields a different graph and the engine re-plans by diffing.
 */
@Component
public class PlanBuilder {

    static final int DEFAULT_RETRIES = 2;

    public List<NodeSpec> build(RequirementModel m) {
        var nodes = new ArrayList<NodeSpec>();
        boolean schema = m.features().stream().anyMatch(Feature::touchesSchema);

        nodes.add(node("intake", "Normalize requirement", "requirements-agent", List.of(), List.of(),
                List.of("requirements.md"), false, "requirements"));
        nodes.add(node("ambiguity-review", "Resolve ambiguity", "ambiguity-agent", List.of("intake"),
                List.of("requirements.md"), List.of("ambiguity-report.md"), false, "requirements"));
        nodes.add(node("decompose", "Decompose into tasks", "decomposition-agent", List.of("ambiguity-review"),
                List.of("requirements.md", "ambiguity-report.md"), List.of("task-plan.md"), false, "requirements"));

        var archDeps = new ArrayList<>(List.of("decompose", "threat-model"));
        var archConsumes = new ArrayList<>(List.of("task-plan.md", "threat-model.md"));
        nodes.add(node("threat-model", "Threat model", "threat-model-agent", List.of("decompose"),
                List.of("requirements.md", "task-plan.md"), List.of("threat-model.md"), false, "design"));
        if (m.brownfield()) {
            nodes.add(node("codebase-analysis", "Codebase impact analysis", "codebase-agent", List.of("decompose"),
                    List.of("requirements.md", "task-plan.md"), List.of("impact-analysis.md"), false, "design"));
            archDeps.add("codebase-analysis");
            archConsumes.add("impact-analysis.md");
        }
        nodes.add(highImpact("architecture", "Architecture and design review", "architecture-agent", archDeps,
                archConsumes, List.of("architecture.md"), "design"));

        var implDeps = new ArrayList<String>();
        var implConsumes = new ArrayList<String>();
        nodes.add(node("api-design", "API contract", "design-agent", List.of("architecture"),
                List.of("architecture.md"), List.of("openapi.yaml"), false, "design"));
        implDeps.add("api-design");
        implConsumes.add("openapi.yaml");
        if (schema) {
            nodes.add(new NodeSpec("schema-migration", "Schema migration", "design-agent", List.of("architecture"),
                    List.of("architecture.md"), List.of("schema-migration.sql"), m.brownfield(),
                    DEFAULT_RETRIES, null, "design"));
            implDeps.add("schema-migration");
            implConsumes.add("schema-migration.sql");
        }
        if (m.has(Feature.ANALYTICS)) {
            nodes.add(node("event-pipeline", "Event pipeline design", "design-agent", List.of("architecture"),
                    List.of("architecture.md"), List.of("event-pipeline.md"), false, "design"));
            implDeps.add("event-pipeline");
            implConsumes.add("event-pipeline.md");
        }
        if (m.has(Feature.AUTH)) {
            nodes.add(node("authz-design", "Authentication and authorization design", "design-agent",
                    List.of("architecture"), List.of("architecture.md"), List.of("authz-design.md"), false, "design"));
            implDeps.add("authz-design");
            implConsumes.add("authz-design.md");
        }
        nodes.add(new NodeSpec("implementation", "Implementation plan and patches", "implementation-agent", implDeps,
                implConsumes, List.of("implementation-plan.md", "code-patches.md"), false, DEFAULT_RETRIES,
                "template-implementation-agent", "build"));

        var implOut = List.of("implementation-plan.md", "code-patches.md");
        nodes.add(node("unit-tests", "Unit tests", "test-agent", List.of("implementation"), implOut,
                List.of("unit-tests.md"), false, "verify"));
        nodes.add(node("integration-tests", "Integration tests", "test-agent", List.of("implementation"), implOut,
                List.of("integration-tests.md"), false, "verify"));
        nodes.add(node("documentation", "Documentation", "docs-agent", List.of("implementation"), implOut,
                List.of("documentation.md"), false, "verify"));
        nodes.add(node("compliance-review", "Policy and compliance review", "compliance-agent",
                List.of("unit-tests", "integration-tests", "documentation", "threat-model"),
                List.of("unit-tests.md", "integration-tests.md", "documentation.md", "threat-model.md"),
                List.of("compliance-report.md"), false, "govern"));
        nodes.add(highImpact("release-readiness", "Release readiness", "release-agent",
                List.of("compliance-review"), List.of("compliance-report.md", "openapi.yaml"),
                List.of("release-checklist.md"), "govern"));

        Set<String> all = new LinkedHashSet<>();
        for (NodeSpec n : nodes) all.addAll(n.produces());
        all.remove("engineering-summary.md");
        nodes.add(node("engineering-summary", "Final engineering summary", "summary-agent",
                List.of("release-readiness"), List.copyOf(all), List.of("engineering-summary.md"), false, "govern"));

        validate(nodes);
        return List.copyOf(nodes);
    }

    private static NodeSpec node(String id, String title, String agent, List<String> deps, List<String> consumes,
                                 List<String> produces, boolean high, String lane) {
        return new NodeSpec(id, title, agent, List.copyOf(deps), List.copyOf(consumes), List.copyOf(produces),
                high, DEFAULT_RETRIES, null, lane);
    }

    private static NodeSpec highImpact(String id, String title, String agent, List<String> deps,
                                       List<String> consumes, List<String> produces, String lane) {
        return node(id, title, agent, deps, consumes, produces, true, lane);
    }

    /** Fail fast on a malformed plan: unknown dependency, cycle, or an artifact nobody produces. */
    static void validate(List<NodeSpec> nodes) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> produced = new LinkedHashSet<>();
        for (NodeSpec n : nodes) {
            if (!ids.add(n.id())) throw new IllegalStateException("Duplicate node " + n.id());
            produced.addAll(n.produces());
        }
        for (NodeSpec n : nodes) {
            for (String d : n.dependsOn())
                if (!ids.contains(d)) throw new IllegalStateException(n.id() + " depends on unknown node " + d);
            for (String c : n.consumes())
                if (!produced.contains(c)) throw new IllegalStateException(n.id() + " consumes unproduced artifact " + c);
        }
        DependencyGraph.topologicalOrder(nodes);   // throws on cycles
    }
}
