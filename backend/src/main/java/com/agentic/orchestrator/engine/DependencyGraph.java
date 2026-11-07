package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.model.Model.NodeSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure graph helpers: ordering, cycle detection, parallel waves and downstream closure. */
public final class DependencyGraph {

    private DependencyGraph() {}

    /** Kahn's algorithm. Throws when the graph has a cycle. */
    public static List<String> topologicalOrder(List<NodeSpec> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (NodeSpec n : nodes) {
            indegree.putIfAbsent(n.id(), 0);
            for (String d : n.dependsOn()) {
                out.computeIfAbsent(d, k -> new ArrayList<>()).add(n.id());
                indegree.merge(n.id(), 1, Integer::sum);
            }
        }
        var ready = new ArrayList<String>();
        indegree.forEach((id, deg) -> { if (deg == 0) ready.add(id); });
        var order = new ArrayList<String>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            order.add(id);
            for (String next : out.getOrDefault(id, List.of()))
                if (indegree.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        if (order.size() != nodes.size()) throw new IllegalStateException("Dependency cycle in plan");
        return order;
    }

    /** Groups nodes into waves; every node in a wave can run in parallel once the previous wave is done. */
    public static List<List<String>> waves(List<NodeSpec> nodes) {
        Map<String, Integer> level = new HashMap<>();
        for (String id : topologicalOrder(nodes)) {
            NodeSpec n = nodes.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            int lvl = n.dependsOn().stream().mapToInt(d -> level.get(d) + 1).max().orElse(0);
            level.put(id, lvl);
        }
        int max = level.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var waves = new ArrayList<List<String>>();
        for (int i = 0; i <= max; i++) {
            int w = i;
            waves.add(level.entrySet().stream().filter(e -> e.getValue() == w).map(Map.Entry::getKey).sorted().toList());
        }
        return waves;
    }

    /** All nodes that transitively depend on the given roots (excluding the roots). */
    public static Set<String> downstream(List<NodeSpec> nodes, Set<String> roots) {
        var result = new LinkedHashSet<String>();
        var frontier = new ArrayList<>(roots);
        while (!frontier.isEmpty()) {
            String cur = frontier.removeFirst();
            for (NodeSpec n : nodes)
                if (n.dependsOn().contains(cur) && result.add(n.id())) frontier.add(n.id());
        }
        return result;
    }
}
