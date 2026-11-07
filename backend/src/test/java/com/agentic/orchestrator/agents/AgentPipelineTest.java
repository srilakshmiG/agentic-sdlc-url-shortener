package com.agentic.orchestrator.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.agents.Agent.AgentContext;
import com.agentic.orchestrator.agents.Agent.AgentResult;
import com.agentic.orchestrator.api.ScenarioCatalog;
import com.agentic.orchestrator.engine.DependencyGraph;
import com.agentic.orchestrator.engine.PlanBuilder;
import com.agentic.orchestrator.engine.PolicyEngine;
import com.agentic.orchestrator.model.Model.Artifact;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.RequirementAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the real agents through a real plan, in dependency order, and holds them to the exit gates. */
class AgentPipelineTest {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();
    private final PlanBuilder planner = new PlanBuilder();
    private final PolicyEngine policy = new PolicyEngine();
    private final ScenarioCatalog scenarios = new ScenarioCatalog();

    private Map<String, Agent> registry() {
        var list = List.<Agent>of(new RequirementsAgent(), new AmbiguityAgent(), new DecompositionAgent(planner),
                new CodebaseAgent(sourceRoot().toString()), new ThreatModelAgent(), new ArchitectureAgent(), new DesignAgent(),
                new ImplementationAgent(), new TemplateImplementationAgent(), new TestAgent(), new DocsAgent(),
                new ComplianceAgent(), new ReleaseAgent(), new SummaryAgent());
        return list.stream().collect(Collectors.toMap(Agent::id, a -> a));
    }

    private static Path sourceRoot() {
        for (String p : new String[]{"src/main", "backend/src/main", System.getenv().getOrDefault("CODEBASE_PATH", "src/main")})
            if (Files.isDirectory(Path.of(p))) return Path.of(p);
        return Path.of("src/main");
    }

    /** Executes every node in order and returns all produced artifacts by name. */
    private Map<String, String> runAll(String requirement, String mode, boolean useFallback) throws Exception {
        var model = analyzer.analyze(requirement, mode);
        List<NodeSpec> plan = planner.build(model);
        var agents = registry();
        Map<String, Artifact> produced = new LinkedHashMap<>();
        for (String id : DependencyGraph.topologicalOrder(plan)) {
            NodeSpec spec = plan.stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
            String agentId = useFallback && spec.fallbackAgent() != null ? spec.fallbackAgent() : spec.agent();
            Agent agent = agents.get(agentId);
            assertNotNull(agent, "no agent " + agentId);
            Map<String, Artifact> inputs = new LinkedHashMap<>();
            for (String c : spec.consumes()) inputs.put(c, produced.get(c));
            assertFalse(inputs.containsValue(null), id + " is missing inputs " + spec.consumes());
            AgentResult r = agent.execute(new AgentContext(UUID.randomUUID(), spec, model, 1, inputs, null, 0));
            var violations = policy.checkResult(spec, agent, r, model);
            assertTrue(violations.isEmpty(), id + " violates policy: " + PolicyEngine.feedback(violations));
            assertTrue(r.questions().isEmpty(), id + " unexpectedly asked for clarification");
            for (var d : r.artifacts())
                produced.put(d.name(), new Artifact(0, UUID.randomUUID(), d.name(), 1, id, d.contentType(), d.content(), "h", List.of(), "ACTIVE", null));
        }
        return produced.values().stream().collect(Collectors.toMap(Artifact::name, Artifact::content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENFIELD", "BROWNFIELD"})
    void clearScenariosPassEveryGate(String id) throws Exception {
        var s = scenarios.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        var out = runAll(s.requirement(), id, false);
        assertTrue(out.get("compliance-report.md").contains("RESULT: PASS"));
        assertTrue(out.get("engineering-summary.md").contains("## Limitations"));
        assertTrue(out.get("openapi.yaml").startsWith("openapi: 3.1.0"));
    }

    @Test
    void ambiguousScenarioPassesOnceDefaultsAreConfirmed() throws Exception {
        String text = scenarios.all().stream().filter(x -> x.id().equals("AMBIGUOUS")).findFirst().orElseThrow().requirement();
        var open = analyzer.analyze(text, null).openQuestions();
        var sb = new StringBuilder(text).append("\n\nClarifications:");
        open.forEach(q -> sb.append("\n[").append(q.slot()).append("] ").append(q.assumption()));
        var out = runAll(sb.toString(), null, false);
        assertTrue(out.containsKey("event-pipeline.md"), "the confirmed answer mentions analytics, which adds a node");
        assertTrue(out.get("compliance-report.md").contains("RESULT: PASS"));
    }

    @Test
    void fallbackImplementationAgentStillSatisfiesTheGates() throws Exception {
        var s = scenarios.all().getFirst();
        var out = runAll(s.requirement(), "GREENFIELD", true);
        assertTrue(out.get("implementation-plan.md").contains("conservative fallback"));
    }

    @Test
    void ambiguousAgentAsksInsteadOfGuessing() throws Exception {
        String text = scenarios.all().stream().filter(x -> x.id().equals("AMBIGUOUS")).findFirst().orElseThrow().requirement();
        var model = analyzer.analyze(text, null);
        var spec = planner.build(model).stream().filter(n -> n.id().equals("ambiguity-review")).findFirst().orElseThrow();
        var r = new AmbiguityAgent().execute(new AgentContext(UUID.randomUUID(), spec, model, 1, Map.of(), null, 0));
        assertEquals(3, r.questions().size());
    }

    @Test
    void codebaseAgentFindsRealCodeAndExcludesTheOrchestratorItself() throws Exception {
        var model = analyzer.analyze(scenarios.all().get(1).requirement(), "BROWNFIELD");
        var spec = planner.build(model).stream().filter(n -> n.id().equals("codebase-analysis")).findFirst().orElseThrow();
        var r = new CodebaseAgent(sourceRoot().toString())
                .execute(new AgentContext(UUID.randomUUID(), spec, model, 1, Map.of(), null, 0));
        String doc = r.artifacts().getFirst().content();
        assertTrue(doc.contains("RedirectController.java"));
        assertTrue(doc.contains("LinkService.java"));
        assertTrue(doc.contains("already present"));
        assertFalse(doc.contains("OrchestratorController"), "the tooling is not part of the product under change");
    }
}
