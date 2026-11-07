package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.model.Feature;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Tiny markdown helpers shared by the agents. */
final class Md {

    private Md() {}

    static String bullets(Collection<String> items) {
        if (items.isEmpty()) return "- none\n";
        return items.stream().map(i -> "- " + i).collect(Collectors.joining("\n", "", "\n"));
    }

    static String numbered(List<String> items) {
        return IntStream.range(0, items.size()).mapToObj(i -> (i + 1) + ". " + items.get(i))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    static String acId(Feature f, int index) {
        return "AC-" + f.name() + "-" + (index + 1);
    }

    static String featureList(Collection<Feature> features) {
        return features.stream().map(f -> f.label).collect(Collectors.joining(", "));
    }
}
