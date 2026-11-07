package com.agentic.orchestrator.api;

import com.agentic.orchestrator.model.Model.Fault;
import com.agentic.orchestrator.model.Model.FaultMode;
import java.util.List;
import org.springframework.stereotype.Component;

/** The three assessment scenarios, ready to launch with one click. */
@Component
public class ScenarioCatalog {

    public record Scenario(String id, String title, String kind, String summary, String requirement,
                           List<String> demonstrates, List<Fault> suggestedFaults) {}

    private final List<Scenario> all = List.of(
            new Scenario("GREENFIELD", "Greenfield: build the shortener", "Greenfield",
                    "A clear requirement for a new system. Shows decomposition, parallel design and verification paths, human approvals and a full audit trail.",
                    "Build a URL shortener service from scratch. Users can shorten a long URL into a short link and be redirected when they open it. "
                            + "Support custom aliases and link expiry. Count clicks through a Kafka event pipeline and show per-link analytics, "
                            + "retaining click data for 90 days without storing raw IP addresses. Protect link creation with rate limiting, "
                            + "validate target URLs against unsafe destinations, and make redirects resilient so an analytics outage never breaks them.",
                    List.of("Parallel threat model and design branches", "Fan-in compliance gate", "Approvals on architecture and release",
                            "Retry then success when a transient fault is injected"),
                    List.of(new Fault("implementation", FaultMode.TRANSIENT, 2))),
            new Scenario("BROWNFIELD", "Brownfield: change the existing code", "Brownfield",
                    "An enhancement to the code in this repository. The codebase agent reads the real source tree to find impacted files, endpoints, tables and topics, and detects what already exists.",
                    "In the existing URL shortener, add link expiry and custom aliases to the create-link API, make sure expired or disabled links never "
                            + "redirect, and keep click counts flowing through the Kafka click pipeline after the change. Keep the current API backward "
                            + "compatible and document the schema migration.",
                    List.of("Codebase reasoning over real files", "High-impact schema migration needs approval", "Backward compatibility analysis",
                            "Fallback agent when the primary implementation agent is down"),
                    List.of(new Fault("implementation", FaultMode.PRIMARY_DOWN, 0))),
            new Scenario("AMBIGUOUS", "Ambiguous: vague request", "Ambiguous",
                    "A vague one-liner. The run pauses for clarification, then re-plans when a human answers, adding or changing graph nodes.",
                    "Make our short links smarter and faster, and keep everything secure.",
                    List.of("Ambiguity detection with proposed defaults", "Pause for human clarification", "Dynamic re-plan after the answer",
                            "Early cutoff: unchanged outputs are not rebuilt"),
                    List.of()));

    public List<Scenario> all() { return all; }
}
