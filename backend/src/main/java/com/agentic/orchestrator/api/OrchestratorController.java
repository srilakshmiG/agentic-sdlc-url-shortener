package com.agentic.orchestrator.api;

import com.agentic.orchestrator.engine.AuditLog;
import com.agentic.orchestrator.engine.DependencyGraph;
import com.agentic.orchestrator.engine.MetricsService;
import com.agentic.orchestrator.engine.RunStore;
import com.agentic.orchestrator.engine.WorkflowEngine;
import com.agentic.orchestrator.model.Model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orchestrator")
public class OrchestratorController {

    public record StartRunRequest(@Size(max = 200) String title, @NotBlank @Size(max = 8000) String requirement,
                                  @Size(max = 20) String scenario, @Size(max = 10) List<@Valid FaultRequest> faults,
                                  @Size(max = 80) String actor) {}

    public record FaultRequest(@NotBlank String stage, FaultMode mode, int count) {}

    public record DecisionRequest(@NotBlank String decision, @Size(max = 80) String by, @Size(max = 2000) String comment,
                                  Map<String, String> answers) {}

    public record AmendRequest(@NotBlank @Size(max = 8000) String requirement, @Size(max = 80) String by) {}

    public record StopRequest(@Size(max = 80) String by, @Size(max = 500) String reason, boolean rollback) {}

    public record ArtifactSummary(String name, int version, String stageId, String status, String contentType,
                                  List<String> derivedFrom, Instant createdAt, int bytes) {}

    public record RunDetail(Run run, List<Stage> stages, List<List<String>> waves, List<Approval> approvals,
                            List<Decision> decisions, List<ArtifactSummary> artifacts) {}

    private final WorkflowEngine engine;
    private final RunStore store;
    private final AuditLog audit;
    private final MetricsService metrics;
    private final ScenarioCatalog scenarios;

    public OrchestratorController(WorkflowEngine engine, RunStore store, AuditLog audit, MetricsService metrics,
                                  ScenarioCatalog scenarios) {
        this.engine = engine;
        this.store = store;
        this.audit = audit;
        this.metrics = metrics;
        this.scenarios = scenarios;
    }

    @GetMapping("/scenarios")
    public List<ScenarioCatalog.Scenario> scenarios() { return scenarios.all(); }

    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRunRequest req) {
        var faults = req.faults() == null ? List.<Fault>of()
                : req.faults().stream().map(f -> new Fault(f.stage(), f.mode() == null ? FaultMode.TRANSIENT : f.mode(),
                        Math.max(0, Math.min(f.count(), 10)))).toList();
        UUID id = engine.startRun(new WorkflowEngine.StartRequest(req.title(), req.requirement(), req.scenario(), faults, req.actor()));
        return ResponseEntity.created(URI.create("/api/v1/orchestrator/runs/" + id)).body(Map.of("id", id));
    }

    @GetMapping("/runs")
    public List<Run> runs() { return store.runs(); }

    @GetMapping("/runs/{id}")
    public RunDetail run(@PathVariable UUID id) {
        Run run = store.run(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
        var stages = store.stages(id);
        var specs = stages.stream().map(s -> s.spec).toList();
        var arts = store.artifacts(id).stream().map(a -> new ArtifactSummary(a.name(), a.version(), a.stageId(), a.status(),
                a.contentType(), a.derivedFrom(), a.createdAt(), a.content().length())).toList();
        return new RunDetail(run, stages, DependencyGraph.waves(specs), store.approvals(id), store.decisions(id), arts);
    }

    @GetMapping(value = "/runs/{id}/artifacts/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String artifact(@PathVariable UUID id, @PathVariable String name, @RequestParam(required = false) Integer version) {
        return store.artifact(id, name, version).orElseThrow(() -> new NoSuchElementException("Artifact not found")).content();
    }

    @GetMapping("/runs/{id}/audit")
    public List<AuditEvent> audit(@PathVariable UUID id) {
        store.run(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
        return audit.events(id);
    }

    @GetMapping("/runs/{id}/audit/verify")
    public AuditLog.Verification verify(@PathVariable UUID id) {
        store.run(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
        return audit.verify(id);
    }

    @PostMapping("/runs/{id}/approvals/{approvalId}/decision")
    public ResponseEntity<Void> decide(@PathVariable UUID id, @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest req) {
        engine.decide(id, approvalId, req.decision(), req.by(), req.comment(), req.answers());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/runs/{id}/requirement")
    public ResponseEntity<Void> amend(@PathVariable UUID id, @Valid @RequestBody AmendRequest req) {
        engine.amendRequirement(id, req.requirement(), req.by());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/runs/{id}/safe-stop")
    public ResponseEntity<Void> stop(@PathVariable UUID id, @Valid @RequestBody StopRequest req) {
        engine.safeStop(id, req.by(), req.reason(), req.rollback());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/metrics")
    public MetricsService.Metrics metrics() { return metrics.compute(); }
}
