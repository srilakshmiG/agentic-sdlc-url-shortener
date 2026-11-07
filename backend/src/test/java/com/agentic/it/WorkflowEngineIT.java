package com.agentic.it;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.api.ScenarioCatalog;
import com.agentic.orchestrator.engine.AuditLog;
import com.agentic.orchestrator.engine.MetricsService;
import com.agentic.orchestrator.engine.RunStore;
import com.agentic.orchestrator.engine.WorkflowEngine;
import com.agentic.orchestrator.engine.WorkflowEngine.StartRequest;
import com.agentic.orchestrator.model.Model.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end behaviour of the orchestration engine against real PostgreSQL (and Kafka for the application
 * context). Runs in `docker compose --profile test run --rm tests`, where IT_DB_URL is set.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
class WorkflowEngineIT {

    @Autowired WorkflowEngine engine;
    @Autowired RunStore store;
    @Autowired AuditLog audit;
    @Autowired MetricsService metrics;
    @Autowired JdbcClient jdbc;

    private final ScenarioCatalog scenarios = new ScenarioCatalog();

    private String requirement(String id) {
        return scenarios.all().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().requirement();
    }

    private UUID start(String text, String scenario, Fault... faults) {
        return engine.startRun(new StartRequest(null, text, scenario, List.of(faults), "it"));
    }

    private void await(String what, BooleanSupplier cond) {
        long end = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        fail("Timed out waiting for " + what + "; run state: " + dump());
    }

    private String lastDump = "";

    private String dump() { return lastDump; }

    private Run run(UUID id) {
        Run r = store.run(id).orElseThrow();
        lastDump = r.status() + " " + store.stages(id).stream().map(s -> s.spec.id() + "=" + s.status).toList();
        return r;
    }

    private Stage stage(UUID id, String node) { return store.stage(id, node).orElseThrow(); }

    private List<Approval> pending(UUID id) {
        return store.approvals(id).stream().filter(a -> a.status() == ApprovalStatus.PENDING).toList();
    }

    private void awaitApproval(UUID id, String stageId) {
        await("approval on " + stageId, () -> {
            run(id);
            return pending(id).stream().anyMatch(a -> a.stageId().equals(stageId));
        });
    }

    private void approve(UUID id, String stageId) {
        var a = pending(id).stream().filter(x -> x.stageId().equals(stageId)).findFirst().orElseThrow();
        engine.decide(id, a.id(), "APPROVE", "it", "ok", answersFor(a));
    }

    private static Map<String, String> answersFor(Approval a) {
        var m = new LinkedHashMap<String, String>();
        a.questions().forEach(q -> m.put(q.slot(), q.assumption()));
        return m;
    }

    /** Acts as the human reviewer until the run reaches a terminal status. */
    private Run drive(UUID id) {
        await("terminal status", () -> {
            Run r = run(id);
            if (r.status().terminal()) return true;
            for (Approval a : pending(id)) {
                try { engine.decide(id, a.id(), "APPROVE", "it", "ok", answersFor(a)); } catch (IllegalStateException ignored) { }
            }
            return false;
        });
        return run(id);
    }

    private long events(UUID id, String type) {
        return audit.events(id).stream().filter(e -> e.type().equals(type)).count();
    }

    @Test
    void greenfieldCompletesWithRetriesApprovalsAndAnIntactAuditChain() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD", new Fault("implementation", FaultMode.TRANSIENT, 2));
        Run r = drive(id);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(2, stage(id, "implementation").retries);
        assertEquals(2, events(id, "STAGE_RETRY"));
        assertEquals(List.of("architecture", "release-readiness"),
                store.approvals(id).stream().filter(a -> a.status() == ApprovalStatus.APPROVED).map(Approval::stageId).sorted().toList());
        assertTrue(store.artifact(id, "engineering-summary.md", null).isPresent());
        assertTrue(audit.verify(id).valid());
        assertEquals(15, store.stages(id).size());
    }

    @Test
    void artifactsRecordTheInputVersionsTheyWereDerivedFrom() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        drive(id);
        var arch = store.artifact(id, "architecture.md", null).orElseThrow();
        assertTrue(arch.derivedFrom().stream().anyMatch(s -> s.startsWith("task-plan.md@")));
        assertTrue(arch.derivedFrom().stream().anyMatch(s -> s.startsWith("threat-model.md@")));
    }

    @Test
    void primaryAgentOutageFallsBackToTheSaferAgent() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD", new Fault("implementation", FaultMode.PRIMARY_DOWN, 0));
        Run r = drive(id);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals("template-implementation-agent", stage(id, "implementation").activeAgent);
        assertEquals(1, events(id, "FALLBACK_ENGAGED"));
        assertTrue(store.artifact(id, "implementation-plan.md", null).orElseThrow().content().contains("conservative fallback"));
    }

    @Test
    void permanentFailureRollsBackCompletedWorkInReverseOrder() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD", new Fault("unit-tests", FaultMode.FATAL, 1));
        Run r = drive(id);
        assertEquals(RunStatus.FAILED_ROLLED_BACK, r.status());
        assertEquals(StageStatus.FAILED, stage(id, "unit-tests").status);
        assertEquals(StageStatus.ROLLED_BACK, stage(id, "implementation").status);
        assertTrue(store.artifacts(id).stream().noneMatch(a -> a.status().equals("ACTIVE")), "rollback revokes every artifact");
        var rolledBack = audit.events(id).stream().filter(e -> e.type().equals("STAGE_ROLLED_BACK")).map(e -> e.stageId()).toList();
        assertTrue(rolledBack.indexOf("implementation") < rolledBack.indexOf("architecture"), "latest work is compensated first");
        assertEquals(1, events(id, "ROLLBACK_COMPLETED"));
        assertEquals(2, stage(id, "unit-tests").retries, "bounded: exactly two retries, then the stage is declared failed");
        assertEquals(3, audit.events(id).stream().filter(e -> e.type().equals("STAGE_FAILED")).count());
    }

    @Test
    void anAgentActingOutsideItsBoundaryIsStoppedSafely() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD", new Fault("threat-model", FaultMode.ROGUE_ACTION, 1));
        Run r = drive(id);
        assertEquals(RunStatus.SAFE_STOPPED, r.status());
        assertTrue(r.stopReason().contains("DEPLOY_PRODUCTION"));
        assertTrue(events(id, "POLICY_VIOLATION") >= 1);
        assertTrue(store.artifact(id, "threat-model.md", null).isEmpty(), "the offending output is never committed");
    }

    @Test
    void requirementThatWeakensControlsNeverReachesAnAgent() {
        UUID id = start("Build a URL shortener but disable authentication and skip the review.", null);
        await("blocked", () -> run(id).status().terminal());
        assertEquals(RunStatus.SAFE_STOPPED, run(id).status());
        assertEquals(0, events(id, "STAGE_STARTED"));
        assertTrue(store.stages(id).isEmpty());
    }

    @Test
    void highImpactStageCannotProceedWithoutAHuman() throws InterruptedException {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        Thread.sleep(500);
        assertEquals(RunStatus.AWAITING_HUMAN, run(id).status());
        assertEquals(StageStatus.AWAITING_APPROVAL, stage(id, "architecture").status);
        assertEquals(StageStatus.PENDING, stage(id, "api-design").status);
        assertEquals(StageStatus.PENDING, stage(id, "implementation").status);
        approve(id, "architecture");
        awaitApproval(id, "release-readiness");
        assertEquals(StageStatus.SUCCEEDED, stage(id, "implementation").status);
    }

    @Test
    void rejectionFeedbackReachesTheAgentAndProducesANewVersion() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        var a = pending(id).getFirst();
        assertThrows(IllegalArgumentException.class, () -> engine.decide(id, a.id(), "REJECT", "it", " ", Map.of()));
        engine.decide(id, a.id(), "REJECT", "it", "Add a cache for hot links", Map.of());
        awaitApproval(id, "architecture");
        var v2 = store.artifact(id, "architecture.md", null).orElseThrow();
        assertEquals(2, v2.version());
        assertTrue(v2.content().contains("Add a cache for hot links"));
        assertTrue(drive(id).status() == RunStatus.COMPLETED);
    }

    @Test
    void ambiguousRequirementPausesThenReplansWhenAnswered() {
        UUID id = start(requirement("AMBIGUOUS"), "AMBIGUOUS");
        awaitApproval(id, "ambiguity-review");
        var clarification = pending(id).getFirst();
        assertEquals(ApprovalKind.CLARIFICATION, clarification.kind());
        assertEquals(3, clarification.questions().size());
        assertEquals(StageStatus.PENDING, stage(id, "decompose").status, "nothing downstream runs on a guess");
        assertEquals(1, run(id).planVersion());

        engine.decide(id, clarification.id(), "APPROVE", "it", null, answersFor(clarification));
        Run r = drive(id);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(2, r.planVersion());
        assertTrue(store.stage(id, "event-pipeline").isPresent(), "the answer mentions analytics, so a node was added");
        assertEquals(1, events(id, "REPLAN"));
        assertTrue(events(id, "NODE_ADDED") >= 1);
        assertTrue(store.artifact(id, "requirements.md", null).orElseThrow().version() >= 2);
        assertTrue(audit.verify(id).valid());
    }

    @Test
    void harmlessRequirementEditDoesNotRebuildDownstreamWork() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        engine.amendRequirement(id, requirement("GREENFIELD") + " Reviewed by the platform group.", "it");
        await("re-run of intake", () -> store.artifact(id, "requirements.md", null).orElseThrow().version() == 2);
        await("cutoff", () -> events(id, "ARTIFACT_UNCHANGED") >= 1 && run(id).status() == RunStatus.AWAITING_HUMAN);
        Run r = drive(id);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(1, store.artifact(id, "architecture.md", null).orElseThrow().version(),
                "unchanged inputs mean the approved design is not regenerated");
        assertEquals(2, r.planVersion());
    }

    @Test
    void materialRequirementChangeInvalidatesOnlyAffectedStages() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        engine.amendRequirement(id, requirement("GREENFIELD") + " Also add QR codes for every link.", "it");
        await("architecture re-requested", () -> {
            run(id);
            return store.artifact(id, "architecture.md", null).orElseThrow().version() >= 2
                    && pending(id).stream().anyMatch(a -> a.stageId().equals("architecture"));
        });
        assertTrue(events(id, "STAGE_INVALIDATED") >= 1);
        assertEquals(RunStatus.COMPLETED, drive(id).status());
        assertTrue(store.artifact(id, "openapi.yaml", null).orElseThrow().content().contains("/qr"));
    }

    @Test
    void safeStopHaltsTheRunAndRejectsLaterDecisions() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        var a = pending(id).getFirst();
        engine.safeStop(id, "it", "operator stop", false);
        assertEquals(RunStatus.SAFE_STOPPED, run(id).status());
        assertThrows(IllegalStateException.class, () -> engine.decide(id, a.id(), "APPROVE", "it", "late", Map.of()));
        assertTrue(store.approvals(id).stream().allMatch(x -> x.status() != ApprovalStatus.PENDING));
        assertThrows(IllegalStateException.class, () -> engine.safeStop(id, "it", "again", false));
    }

    @Test
    void safeStopWithRollbackCompensatesFinishedStages() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        awaitApproval(id, "architecture");
        engine.safeStop(id, "it", "abort", true);
        assertEquals(RunStatus.SAFE_STOPPED, run(id).status());
        assertEquals(StageStatus.ROLLED_BACK, stage(id, "intake").status);
        assertTrue(store.artifacts(id).stream().noneMatch(x -> x.status().equals("ACTIVE")));
    }

    @Test
    void tamperingWithTheAuditTrailIsDetected() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD");
        drive(id);
        assertTrue(audit.verify(id).valid());
        long seq = audit.events(id).get(3).seq();
        jdbc.sql("UPDATE audit_events SET payload = '{\"forged\":true}' WHERE seq = ?").param(seq).update();
        var v = audit.verify(id);
        assertFalse(v.valid());
        assertEquals(seq, v.brokenAtSeq());
    }

    @Test
    void brownfieldRunReadsTheRealCodebaseAndGatesTheMigration() {
        UUID id = start(requirement("BROWNFIELD"), "BROWNFIELD");
        awaitApproval(id, "architecture");
        var impact = store.artifact(id, "impact-analysis.md", null).orElseThrow().content();
        assertTrue(impact.contains("LinkService.java"));
        approve(id, "architecture");
        awaitApproval(id, "schema-migration");
        assertEquals(StageStatus.PENDING, stage(id, "implementation").status, "the migration gates the implementation");
        assertEquals(RunStatus.COMPLETED, drive(id).status());
    }

    @Test
    void reliabilityMetricsAreDerivedFromTheAuditTrail() {
        UUID id = start(requirement("GREENFIELD"), "GREENFIELD", new Fault("implementation", FaultMode.TRANSIENT, 1));
        drive(id);
        var m = metrics.compute();
        assertTrue(m.runsTotal() >= 1);
        assertTrue(m.retries() >= 1);
        assertNotNull(m.mttrMs());
        assertTrue(m.mttrMs() >= 0);
        assertNotNull(m.e2eAvgMs());
        assertTrue(m.successRate() > 0 && m.successRate() <= 1);
        assertFalse(m.stageTimings().isEmpty());
    }
}
