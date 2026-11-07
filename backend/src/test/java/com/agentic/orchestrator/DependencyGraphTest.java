package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.engine.DependencyGraph;
import com.agentic.orchestrator.model.Model.NodeSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    private static NodeSpec n(String id, String... deps) {
        return new NodeSpec(id, id, "a", List.of(deps), List.of(), List.of(), false, 2, null, "x");
    }

    @Test
    void ordersAndGroupsIntoParallelWaves() {
        var nodes = List.of(n("a"), n("b", "a"), n("c", "a"), n("d", "b", "c"));
        var order = DependencyGraph.topologicalOrder(nodes);
        assertTrue(order.indexOf("a") < order.indexOf("b"));
        assertTrue(order.indexOf("c") < order.indexOf("d"));
        assertEquals(List.of(List.of("a"), List.of("b", "c"), List.of("d")), DependencyGraph.waves(nodes));
    }

    @Test
    void detectsCycles() {
        var nodes = List.of(n("a", "c"), n("b", "a"), n("c", "b"));
        assertThrows(IllegalStateException.class, () -> DependencyGraph.topologicalOrder(nodes));
    }

    @Test
    void downstreamClosureExcludesRoots() {
        var nodes = List.of(n("a"), n("b", "a"), n("c", "b"), n("x"));
        assertEquals(Set.of("b", "c"), DependencyGraph.downstream(nodes, Set.of("a")));
    }
}
