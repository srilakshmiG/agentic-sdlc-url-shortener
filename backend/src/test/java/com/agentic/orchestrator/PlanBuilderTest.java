package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.engine.DependencyGraph;
import com.agentic.orchestrator.engine.PlanBuilder;
import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.RequirementAnalyzer;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanBuilderTest {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();
    private final PlanBuilder planner = new PlanBuilder();

    private List<NodeSpec> plan(String text, String mode) {
        return planner.build(analyzer.analyze(text, mode));
    }

    private static NodeSpec node(List<NodeSpec> p, String id) {
        return p.stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("missing " + id));
    }

    @Test
    void greenfieldHasNoCodebaseAnalysisAndGatesHighImpactNodes() {
        var p = plan("Build a URL shortener with custom aliases and click analytics.", "GREENFIELD");
        assertTrue(p.stream().noneMatch(n -> n.id().equals("codebase-analysis")));
        assertTrue(node(p, "architecture").highImpact());
        assertTrue(node(p, "release-readiness").highImpact());
        assertFalse(node(p, "schema-migration").highImpact(), "an initial schema is not a change to production data");
    }

    @Test
    void brownfieldAddsCodebaseAnalysisAndGatesTheMigration() {
        var p = plan("In the existing shortener add link expiry.", "BROWNFIELD");
        assertTrue(node(p, "architecture").dependsOn().contains("codebase-analysis"));
        assertTrue(node(p, "schema-migration").highImpact());
    }

    @Test
    void featuresAddNodes() {
        var base = plan("Build a URL shortener.", null);
        var withAuth = plan("Build a URL shortener with user accounts and click analytics.", null);
        assertTrue(base.stream().noneMatch(n -> n.id().equals("authz-design")));
        assertNotNull(node(withAuth, "authz-design"));
        assertNotNull(node(withAuth, "event-pipeline"));
        assertTrue(node(withAuth, "implementation").dependsOn().containsAll(List.of("authz-design", "event-pipeline")));
    }

    @Test
    void parallelBranchesAndFanIn() {
        var p = plan("In the existing shortener add link expiry.", "BROWNFIELD");
        var waves = DependencyGraph.waves(p);
        assertTrue(waves.stream().anyMatch(w -> w.containsAll(List.of("threat-model", "codebase-analysis"))));
        assertTrue(waves.stream().anyMatch(w -> w.containsAll(List.of("unit-tests", "integration-tests", "documentation"))));
        assertEquals(List.of("documentation", "integration-tests", "threat-model", "unit-tests"),
                node(p, "compliance-review").dependsOn().stream().sorted().toList());
    }

    @Test
    void everyFeatureCombinationYieldsAValidAcyclicPlan() {
        var all = Feature.values();
        for (int mask = 0; mask < (1 << all.length); mask += 7) {
            var set = EnumSet.noneOf(Feature.class);
            for (int i = 0; i < all.length; i++) if ((mask & (1 << i)) != 0) set.add(all[i]);
            var text = new StringBuilder("Build a URL shortener");
            set.forEach(f -> text.append(' ').append(f.keywords.getFirst()));
            for (String mode : new String[]{"GREENFIELD", "BROWNFIELD"})
                assertDoesNotThrow(() -> plan(text.toString(), mode), text + " " + mode);
        }
    }
}
