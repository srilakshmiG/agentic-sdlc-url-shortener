package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.agents.Agent;
import com.agentic.orchestrator.agents.Agent.AgentResult;
import com.agentic.orchestrator.agents.Agent.ArtifactDraft;
import com.agentic.orchestrator.engine.AuditLog;
import com.agentic.orchestrator.engine.PolicyEngine;
import com.agentic.orchestrator.model.Model.AuditEvent;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.Model.Severity;
import com.agentic.orchestrator.model.RequirementAnalyzer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditAndPolicyTest {

    private final PolicyEngine policy = new PolicyEngine();
    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();

    private static List<AuditEvent> chain(int n) {
        UUID run = UUID.randomUUID();
        var events = new ArrayList<AuditEvent>();
        String prev = AuditLog.GENESIS;
        for (int i = 1; i <= n; i++) {
            Instant ts = Instant.parse("2030-01-01T00:00:00Z").plusSeconds(i);
            String payload = "{\"i\":" + i + "}";
            String hash = hashOf(prev, run, ts, "system", "E" + i, null, payload);
            events.add(new AuditEvent(i, run, ts, "system", "E" + i, null, payload, prev, hash));
            prev = hash;
        }
        return events;
    }

    private static String hashOf(String prev, UUID run, Instant ts, String actor, String type, String stage, String payload) {
        try {
            var m = AuditLog.class.getDeclaredMethod("hash", String.class, UUID.class, Instant.class, String.class,
                    String.class, String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, prev, run, ts, actor, type, stage, payload);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void intactChainVerifies() {
        assertTrue(AuditLog.verify(chain(8)).valid());
        assertTrue(AuditLog.verify(List.of()).valid());
    }

    @Test
    void alteringAnEventIsDetected() {
        var events = chain(6);
        var e = events.get(3);
        events.set(3, new AuditEvent(e.seq(), e.runId(), e.ts(), e.actor(), e.type(), e.stageId(), "{\"i\":999}", e.prevHash(), e.hash()));
        var v = AuditLog.verify(events);
        assertFalse(v.valid());
        assertEquals(4L, v.brokenAtSeq());
    }

    @Test
    void removingAnEventIsDetected() {
        var events = chain(6);
        events.remove(2);
        var v = AuditLog.verify(events);
        assertFalse(v.valid());
        assertEquals(4L, v.brokenAtSeq());
    }

    private static Agent agent(Set<Agent.Capability> caps) {
        return new Agent() {
            public String id() { return "t"; }
            public Set<Capability> capabilities() { return caps; }
            public AgentResult execute(AgentContext c) { return null; }
        };
    }

    private static NodeSpec spec(String produces) {
        return new NodeSpec("n", "n", "t", List.of(), List.of(), List.of(produces), false, 2, null, "x");
    }

    @Test
    void actionsOutsideTheAutonomyBoundaryAreHardViolations() {
        var result = AgentResult.of("x", Agent.md("requirements.md", "## Acceptance criteria\nok")).withExtraAction("DEPLOY_PRODUCTION");
        var v = policy.checkResult(spec("requirements.md"), agent(Set.of(Agent.Capability.WRITE_ARTIFACT)), result,
                analyzer.analyze("Build a URL shortener.", null));
        assertTrue(PolicyEngine.hasHard(v));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("CHG-002")));
    }

    @Test
    void secretsInArtifactsAreHardViolations() {
        var result = AgentResult.of("x", Agent.md("requirements.md", "## Acceptance criteria\npassword = hunter2hunter2"));
        var v = policy.checkResult(spec("requirements.md"), agent(Set.of(Agent.Capability.WRITE_ARTIFACT)), result,
                analyzer.analyze("Build a URL shortener.", null));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("SEC-001") && x.severity() == Severity.HARD));
    }

    @Test
    void exitGateFailuresAreSoftSoTheStageCanRetry() {
        var result = AgentResult.of("x", new ArtifactDraft("architecture.md", "text/markdown", "# Architecture only"));
        var v = policy.checkResult(spec("architecture.md"), agent(Set.of(Agent.Capability.WRITE_ARTIFACT)), result,
                analyzer.analyze("Build a URL shortener.", null));
        assertFalse(PolicyEngine.hasHard(v));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("GATE-003")));
    }

    @Test
    void missingDeclaredOutputIsAGateFailure() {
        var result = AgentResult.of("x", Agent.md("other.md", "x"));
        var v = policy.checkResult(spec("requirements.md"), agent(Set.of(Agent.Capability.WRITE_ARTIFACT)), result,
                analyzer.analyze("Build a URL shortener.", null));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("GATE-001")));
    }

    @Test
    void requirementsThatWeakenControlsAreBlocked() {
        var v = policy.checkRequirement(analyzer.analyze("Build a shortener and skip the review, deploy to production automatically.", null));
        assertTrue(PolicyEngine.hasHard(v));
    }
}
