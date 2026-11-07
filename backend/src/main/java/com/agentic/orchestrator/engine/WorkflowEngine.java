package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.agents.Agent;
import com.agentic.orchestrator.agents.Agent.AgentContext;
import com.agentic.orchestrator.agents.Agent.AgentResult;
import com.agentic.orchestrator.agents.Agent.ArtifactDraft;
import com.agentic.orchestrator.engine.PolicyEngine.Violation;
import com.agentic.orchestrator.model.Model.*;
import com.agentic.orchestrator.model.RequirementAnalyzer;
import com.agentic.orchestrator.model.RequirementModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stateful, non-linear SDLC orchestrator.
 *
 * <ul>
 *   <li>Explicit dependency graph with entry and exit gates; independent nodes run in parallel and
 *       fan-in nodes wait for every dependency.</li>
 *   <li>All state lives in PostgreSQL; every change is an audit event; every artifact records which
 *       input versions it was derived from.</li>
 *   <li>High-impact nodes and ambiguous requirements stop for a human decision.</li>
 *   <li>Bounded retries with backoff, fallback agent, compensating rollback and safe-stop.</li>
 *   <li>Re-plan on change: a new requirement rebuilds the graph and diffs it; changed artifacts
 *       invalidate only their consumers, and unchanged outputs stop the ripple (early cutoff).</li>
 * </ul>
 * One lock per run serialises state transitions; agent work itself runs outside the lock.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    public record StartRequest(String title, String requirement, String scenario, List<Fault> faults, String actor) {}

    private record Prepared(AgentContext ctx, Agent agent, List<Fault> faults, int attempt, String agentId) {}

    private record Outcome(Long retryAfterMs, boolean again) {
        static final Outcome DONE = new Outcome(null, false);
    }

    private static class PolicyFailure extends RuntimeException {
        PolicyFailure(String m) { super(m); }
    }

    private static class InjectedFault extends RuntimeException {
        InjectedFault(String m) { super(m); }
    }

    private final RunStore store;
    private final AuditLog audit;
    private final PolicyEngine policy;
    private final PlanBuilder planner;
    private final RequirementAnalyzer analyzer;
    private final Map<String, Agent> agents;
    private final TransactionTemplate tx;
    private final long retryBackoffMs;
    private final long stageDelayMs;
    private final int maxRejections;

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public WorkflowEngine(RunStore store, AuditLog audit, PolicyEngine policy, PlanBuilder planner,
                          RequirementAnalyzer analyzer, List<Agent> agentList, TransactionTemplate tx,
                          @Value("${app.orchestrator.retry-backoff-ms}") long retryBackoffMs,
                          @Value("${app.orchestrator.stage-delay-ms}") long stageDelayMs,
                          @Value("${app.orchestrator.max-rejections}") int maxRejections) {
        this.store = store;
        this.audit = audit;
        this.policy = policy;
        this.planner = planner;
        this.analyzer = analyzer;
        this.agents = agentList.stream().collect(Collectors.toMap(Agent::id, a -> a));
        this.tx = tx;
        this.retryBackoffMs = retryBackoffMs;
        this.stageDelayMs = stageDelayMs;
        this.maxRejections = maxRejections;
    }

    // ================================================================= public operations

    public UUID startRun(StartRequest req) {
        String forced = switch (req.scenario() == null ? "" : req.scenario().toUpperCase(Locale.ROOT)) {
            case "BROWNFIELD" -> "BROWNFIELD";
            case "GREENFIELD" -> "GREENFIELD";
            default -> null;
        };
        RequirementModel model = analyzer.analyze(req.requirement(), forced);
        UUID id = UUID.randomUUID();
        String actor = "human:" + orDefault(req.actor(), "operator");
        locked(id, () -> {
            var run = new Run(id, orDefault(req.title(), model.title()), model.text(), 1,
                    orDefault(req.scenario(), "CUSTOM"), model.brownfield() ? "BROWNFIELD" : "GREENFIELD",
                    RunStatus.RUNNING, 1, req.faults() == null ? List.of() : req.faults(), null, now(), null);
            store.insertRun(run);
            audit.append(id, actor, "RUN_CREATED", null, Map.of("scenario", run.scenario(), "mode", run.mode(),
                    "faults", run.faults().size()));
            var violations = policy.checkRequirement(model);
            if (!violations.isEmpty()) {
                recordViolations(id, null, violations);
                safeStopLocked(id, "policy-engine", "Blocked at intake: " + PolicyEngine.feedback(violations), false);
                return null;
            }
            var specs = planner.build(model);
            specs.forEach(s -> store.insertStage(newStage(id, s)));
            audit.append(id, "system", "PLAN_CREATED", null, Map.of("version", 1, "nodes", specs.size(),
                    "waves", DependencyGraph.waves(specs)));
            store.insertDecision(id, null, "system", "Plan v1 with " + specs.size() + " nodes",
                    "Graph built from detected capabilities: " + Md2.features(model) + "; mode " + run.mode(), List.of());
            return null;
        });
        exec.submit(() -> advance(id));
        return id;
    }

    public void decide(UUID runId, UUID approvalId, String decision, String by, String comment, Map<String, String> answers) {
        locked(runId, () -> {
            Run run = requireRun(runId);
            if (run.status().terminal()) throw new IllegalStateException("Run is " + run.status() + "; nothing to decide");
            Approval ap = store.approval(approvalId).filter(a -> a.runId().equals(runId))
                    .orElseThrow(() -> new NoSuchElementException("Approval not found"));
            if (ap.status() != ApprovalStatus.PENDING) throw new IllegalStateException("Approval already " + ap.status());
            boolean approve = "APPROVE".equalsIgnoreCase(decision);
            if (!approve && !"REJECT".equalsIgnoreCase(decision)) throw new IllegalArgumentException("decision must be APPROVE or REJECT");
            String actor = "human:" + orDefault(by, "operator");
            Stage st = store.stage(runId, ap.stageId()).orElseThrow();

            if (ap.kind() == ApprovalKind.CLARIFICATION) {
                if (!approve) {
                    store.decideApproval(approvalId, ApprovalStatus.REJECTED, by, comment, Map.of());
                    audit.append(runId, actor, "HUMAN_REJECTED", st.spec.id(), Map.of("kind", "CLARIFICATION", "comment", orDefault(comment, "")));
                    safeStopLocked(runId, actor, "Clarification declined: " + orDefault(comment, "no reason given"), false);
                    return null;
                }
                var clean = new LinkedHashMap<String, String>();
                for (Question q : ap.questions()) {
                    String a = answers == null ? null : answers.get(q.slot());
                    clean.put(q.slot(), a == null || a.isBlank() ? q.assumption() : a.strip());
                }
                store.decideApproval(approvalId, ApprovalStatus.APPROVED, by, comment, clean);
                audit.append(runId, actor, "HUMAN_ANSWERED", st.spec.id(), Map.copyOf(clean));
                store.insertDecision(runId, st.spec.id(), actor, "Clarified " + clean.size() + " open questions",
                        clean.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining("; ")), List.of());
                String text = run.requirement().contains(RequirementAnalyzer.CLARIFICATIONS) ? run.requirement()
                        : run.requirement() + "\n\n" + RequirementAnalyzer.CLARIFICATIONS;
                var sb = new StringBuilder(text);
                clean.forEach((k, v) -> sb.append("\n[").append(k).append("] ").append(v.replace("\n", " ")));
                replanLocked(withRequirement(run, sb.toString()), "clarification answered", actor);
            } else if (approve) {
                store.decideApproval(approvalId, ApprovalStatus.APPROVED, by, comment, Map.of());
                audit.append(runId, actor, "HUMAN_APPROVED", st.spec.id(), Map.of("comment", orDefault(comment, "")));
                store.insertDecision(runId, st.spec.id(), actor, "Approved " + st.spec.title(), orDefault(comment, "approved without comment"), List.of());
                succeed(st);
            } else {
                if (comment == null || comment.isBlank()) throw new IllegalArgumentException("A rejection needs a comment so the agent can act on it");
                store.decideApproval(approvalId, ApprovalStatus.REJECTED, by, comment, Map.of());
                audit.append(runId, actor, "HUMAN_REJECTED", st.spec.id(), Map.of("comment", comment));
                store.insertDecision(runId, st.spec.id(), actor, "Rejected " + st.spec.title(), comment, List.of());
                st.rejections++;
                if (st.rejections > maxRejections) {
                    store.saveStage(st);
                    safeStopLocked(runId, "system", "Stage " + st.spec.id() + " rejected " + st.rejections + " times; escalating", false);
                    return null;
                }
                st.feedback = comment;
                st.status = StageStatus.PENDING;
                st.attempt = 0;
                st.epoch++;
                st.finishedAt = null;
                st.completedSeq = null;
                store.saveStage(st);
            }
            advanceLocked(runId);
            return null;
        });
    }

    public void amendRequirement(UUID runId, String text, String by) {
        locked(runId, () -> {
            Run run = requireRun(runId);
            if (run.status().terminal()) throw new IllegalStateException("Run is " + run.status() + "; start a new run instead");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Requirement text is required");
            if (text.strip().equals(run.requirement())) return null;
            String actor = "human:" + orDefault(by, "operator");
            var updated = withRequirement(run, text.strip());
            audit.append(runId, actor, "REQUIREMENT_AMENDED", null, Map.of("fromVersion", run.requirementVersion(),
                    "toVersion", updated.requirementVersion()));
            replanLocked(updated, "requirement amended", actor);
            advanceLocked(runId);
            return null;
        });
    }

    public void safeStop(UUID runId, String by, String reason, boolean rollback) {
        locked(runId, () -> {
            Run run = requireRun(runId);
            if (run.status().terminal()) throw new IllegalStateException("Run already " + run.status());
            safeStopLocked(runId, "human:" + orDefault(by, "operator"), orDefault(reason, "operator requested"), rollback);
            return null;
        });
    }

    /** After a restart, work that was in flight is re-queued; durable state makes this safe. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (RunStatus s : List.of(RunStatus.RUNNING, RunStatus.AWAITING_HUMAN)) {
            for (Run r : store.runsWithStatus(s)) {
                locked(r.id(), () -> {
                    int n = 0;
                    for (Stage st : store.stages(r.id())) {
                        if (st.status == StageStatus.RUNNING) {
                            st.status = StageStatus.PENDING;
                            st.epoch++;
                            store.saveStage(st);
                            n++;
                        }
                    }
                    audit.append(r.id(), "system", "RECOVERED", null, Map.of("requeuedStages", n));
                    return null;
                });
                exec.submit(() -> advance(r.id()));
            }
        }
    }

    // ================================================================= scheduling

    private void advance(UUID runId) {
        try {
            locked(runId, () -> { advanceLocked(runId); return null; });
        } catch (Exception e) {
            log.error("advance failed for {}", runId, e);
        }
    }

    private void advanceLocked(UUID runId) {
        Run run = store.run(runId).orElse(null);
        if (run == null || run.status().terminal()) return;
        List<Stage> stages = store.stages(runId);
        Map<String, Stage> byId = stages.stream().collect(Collectors.toMap(s -> s.spec.id(), s -> s));
        int dispatched = 0;
        for (Stage s : stages) {
            if (s.status != StageStatus.PENDING) continue;
            boolean depsDone = s.spec.dependsOn().stream().allMatch(d -> {
                Stage x = byId.get(d);
                return x != null && (x.status == StageStatus.SUCCEEDED || x.status == StageStatus.SKIPPED);
            });
            if (!depsDone) continue;
            Map<String, Artifact> inputs = inputsFor(runId, s.spec);
            if (inputs.size() < new HashSet<>(s.spec.consumes()).size()) {
                String msg = "entry gate: missing inputs " + s.spec.consumes().stream().filter(c -> !inputs.containsKey(c)).toList();
                if (!msg.equals(s.lastError)) {
                    s.lastError = msg;
                    store.saveStage(s);
                    audit.append(runId, "system", "GATE_ENTRY_BLOCKED", s.spec.id(), Map.of("reason", msg));
                }
                continue;
            }
            s.status = StageStatus.RUNNING;
            s.startedAt = now();
            s.finishedAt = null;
            s.lastError = null;
            store.saveStage(s);
            audit.append(runId, "system", "GATE_ENTRY_PASSED", s.spec.id(), Map.of("inputs", versions(inputs)));
            audit.append(runId, "agent:" + s.activeAgent, "STAGE_STARTED", s.spec.id(),
                    Map.of("agent", s.activeAgent, "epoch", s.epoch, "attempt", s.attempt));
            String node = s.spec.id();
            int epoch = s.epoch;
            dispatched++;
            exec.submit(() -> runStage(runId, node, epoch));
        }

        boolean running = stages.stream().anyMatch(s -> s.status == StageStatus.RUNNING);
        boolean waiting = stages.stream().anyMatch(s -> s.status == StageStatus.AWAITING_APPROVAL);
        boolean allDone = stages.stream().allMatch(s -> s.status == StageStatus.SUCCEEDED || s.status == StageStatus.SKIPPED);
        RunStatus next = allDone ? RunStatus.COMPLETED : running ? RunStatus.RUNNING : waiting ? RunStatus.AWAITING_HUMAN : RunStatus.RUNNING;
        if (!allDone && !running && !waiting && dispatched == 0) {
            safeStopLocked(runId, "system", "Deadlock: no stage can run and none awaits a human", false);
            return;
        }
        if (next != run.status()) {
            Instant fin = allDone ? now() : null;
            store.updateRun(new Run(run.id(), run.title(), run.requirement(), run.requirementVersion(), run.scenario(), run.mode(),
                    next, run.planVersion(), run.faults(), run.stopReason(), run.createdAt(), fin));
            audit.append(runId, "system", allDone ? "RUN_COMPLETED" : "RUN_STATUS", null,
                    Map.of("status", next.name(), "durationMs", Duration.between(run.createdAt(), now()).toMillis()));
        }
    }

    // ================================================================= stage execution

    private void runStage(UUID runId, String nodeId, int epoch) {
        try {
            while (true) {
                Prepared p = locked(runId, () -> prepare(runId, nodeId, epoch));
                if (p == null) return;
                long t0 = System.nanoTime();
                sleep(stageDelayMs);
                AgentResult result = null;
                Throwable err = null;
                try {
                    result = invoke(p, nodeId);
                } catch (Throwable e) {
                    err = e;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                AgentResult fr = result;
                Throwable fe = err;
                Outcome o = locked(runId, () -> finish(runId, nodeId, epoch, p, fr, fe, ms));
                if (o == null || o == Outcome.DONE) return;
                if (o.retryAfterMs() != null) sleep(o.retryAfterMs());
            }
        } catch (Throwable t) {
            log.error("Stage {} of run {} crashed", nodeId, runId, t);
            try {
                safeStop(runId, "system", "Engine error in " + nodeId + ": " + t, false);
            } catch (Exception ignored) {
                // run may already be terminal
            }
        }
    }

    private Prepared prepare(UUID runId, String nodeId, int epoch) {
        Run run = store.run(runId).orElse(null);
        if (run == null || run.status().terminal()) return null;
        Stage st = store.stage(runId, nodeId).orElse(null);
        if (st == null || st.epoch != epoch || st.status != StageStatus.RUNNING) return null;
        RequirementModel model = analyzer.analyze(run.requirement(), run.mode());
        var ctx = new AgentContext(runId, st.spec, model, run.requirementVersion(), inputsFor(runId, st.spec), st.feedback, st.attempt);
        Agent agent = agents.get(st.activeAgent);
        if (agent == null) throw new IllegalStateException("Unknown agent " + st.activeAgent);
        return new Prepared(ctx, agent, run.faults(), st.attempt, st.activeAgent);
    }

    private AgentResult invoke(Prepared p, String nodeId) throws Exception {
        boolean rogue = false;
        for (Fault f : p.faults()) {
            if (!f.stage().equals(nodeId)) continue;
            switch (f.mode()) {
                case TRANSIENT -> {
                    if (p.attempt() < f.count()) throw new InjectedFault("Injected transient failure " + (p.attempt() + 1) + " of " + f.count());
                }
                case PRIMARY_DOWN -> {
                    if (p.agentId().equals(p.ctx().spec().agent())) throw new InjectedFault("Injected outage of primary agent " + p.agentId());
                }
                case FATAL -> throw new InjectedFault("Injected permanent failure");
                case ROGUE_ACTION -> rogue = true;
            }
        }
        AgentResult r = p.agent().execute(p.ctx());
        return rogue ? r.withExtraAction("DEPLOY_PRODUCTION") : r;
    }

    private Outcome finish(UUID runId, String nodeId, int epoch, Prepared p, AgentResult result, Throwable err, long ms) {
        Run run = store.run(runId).orElse(null);
        if (run == null || run.status().terminal()) return null;
        Stage st = store.stage(runId, nodeId).orElse(null);
        if (st == null || st.epoch != epoch || st.status != StageStatus.RUNNING) {
            audit.append(runId, "system", "RESULT_DISCARDED", nodeId, Map.of("reason", "plan changed while the agent was working"));
            return null;
        }
        st.activeMs += ms;
        if (err == null) {
            List<Violation> violations = policy.checkResult(st.spec, p.agent(), result, p.ctx().model());
            if (!violations.isEmpty()) recordViolations(runId, nodeId, violations);
            if (PolicyEngine.hasHard(violations)) {
                st.status = StageStatus.FAILED;
                st.lastError = PolicyEngine.feedback(violations);
                st.finishedAt = now();
                store.saveStage(st);
                safeStopLocked(runId, "policy-engine", "Hard policy violation in " + nodeId + ": " + PolicyEngine.feedback(violations), false);
                return null;
            }
            if (!violations.isEmpty()) err = new PolicyFailure(PolicyEngine.feedback(violations));
        }
        Outcome o = err != null ? fail(runId, st, p, err) : commit(runId, st, p, result);
        advanceLocked(runId);
        return o;
    }

    private Outcome fail(UUID runId, Stage st, Prepared p, Throwable err) {
        st.attempt++;
        if (st.firstFailedAt == null) st.firstFailedAt = now();
        String msg = err.getMessage() == null ? err.toString() : err.getMessage();
        st.lastError = msg;
        if (err instanceof PolicyFailure) st.feedback = msg;
        audit.append(runId, "agent:" + st.activeAgent, "STAGE_FAILED", st.spec.id(),
                Map.of("attempt", st.attempt, "agent", st.activeAgent, "error", msg));
        if (st.attempt <= st.spec.maxRetries()) {
            st.retries++;
            long backoff = Math.min(5_000L, retryBackoffMs * (1L << (st.attempt - 1)));
            store.saveStage(st);
            audit.append(runId, "system", "STAGE_RETRY", st.spec.id(), Map.of("attempt", st.attempt, "backoffMs", backoff,
                    "maxRetries", st.spec.maxRetries()));
            return new Outcome(backoff, false);
        }
        String fb = st.spec.fallbackAgent();
        if (fb != null && !st.activeAgent.equals(fb)) {
            audit.append(runId, "system", "FALLBACK_ENGAGED", st.spec.id(), Map.of("from", st.activeAgent, "to", fb));
            store.insertDecision(runId, st.spec.id(), "system", "Fall back to " + fb,
                    st.activeAgent + " failed " + st.attempt + " times (" + msg + "); degrading to a safer agent", List.of());
            st.activeAgent = fb;
            st.attempt = 0;
            st.feedback = null;
            store.saveStage(st);
            return new Outcome(null, true);
        }
        st.status = StageStatus.FAILED;
        st.finishedAt = now();
        store.saveStage(st);
        audit.append(runId, "system", "STAGE_EXHAUSTED", st.spec.id(), Map.of("error", msg));
        failRunWithRollback(runId, "Stage " + st.spec.id() + " exhausted its retries: " + msg);
        return null;
    }

    private Outcome commit(UUID runId, Stage st, Prepared p, AgentResult result) {
        List<String> derived = p.ctx().inputs().values().stream().map(a -> a.name() + "@" + a.version()).sorted().toList();
        Set<String> changed = new LinkedHashSet<>();
        boolean allUnchanged = true;
        String nodeId = st.spec.id();
        for (ArtifactDraft d : result.artifacts()) {
            if (!st.spec.produces().contains(d.name())) continue;
            String hash = sha256(d.content());
            Artifact prev = store.latestActive(runId, d.name()).orElse(null);
            if (prev != null && prev.contentHash().equals(hash)) {
                audit.append(runId, "agent:" + st.activeAgent, "ARTIFACT_UNCHANGED", nodeId,
                        Map.of("name", d.name(), "version", prev.version()));
                continue;
            }
            allUnchanged = false;
            int version = store.maxVersion(runId, d.name()) + 1;
            if (prev != null) {
                store.supersede(runId, d.name());
                changed.add(d.name());
            }
            store.insertArtifact(new Artifact(0, runId, d.name(), version, nodeId, d.contentType(), d.content(), hash, derived, "ACTIVE", now()));
            audit.append(runId, "agent:" + st.activeAgent, "ARTIFACT_CREATED", nodeId,
                    Map.of("name", d.name(), "version", version, "hash", hash.substring(0, 16), "derivedFrom", derived,
                            "bytes", d.content().length()));
        }
        for (var d : result.decisions())
            store.insertDecision(runId, nodeId, "agent:" + st.activeAgent, d.decision(), d.rationale(), derived);

        st.attempt = 0;
        st.feedback = null;
        if (!changed.isEmpty()) invalidateConsumers(runId, changed, nodeId);

        if (!result.questions().isEmpty()) {
            store.cancelPending(runId, nodeId);
            requestApproval(runId, st, ApprovalKind.CLARIFICATION, result.summary(), result.questions());
        } else if (st.spec.needsApproval()) {
            if (allUnchanged && store.hasApproved(runId, nodeId)) {
                audit.append(runId, "system", "APPROVAL_REUSED", nodeId, Map.of("reason", "outputs identical to the approved version"));
                succeed(st);
            } else {
                store.cancelPending(runId, nodeId);
                requestApproval(runId, st, ApprovalKind.APPROVAL, result.summary() + " Artifacts: "
                        + String.join(", ", st.spec.produces()), List.of());
            }
        } else {
            succeed(st);
        }
        return Outcome.DONE;
    }

    private void requestApproval(UUID runId, Stage st, ApprovalKind kind, String summary, List<Question> questions) {
        var ap = new Approval(UUID.randomUUID(), runId, st.spec.id(), kind, ApprovalStatus.PENDING, summary, questions,
                Map.of(), null, null, now(), null);
        store.insertApproval(ap);
        st.status = StageStatus.AWAITING_APPROVAL;
        st.finishedAt = null;
        store.saveStage(st);
        audit.append(runId, "system", "APPROVAL_REQUESTED", st.spec.id(),
                Map.of("approvalId", ap.id().toString(), "kind", kind.name(), "summary", summary));
    }

    private void succeed(Stage st) {
        st.status = StageStatus.SUCCEEDED;
        st.finishedAt = now();
        st.completedSeq = store.nextCompletionSeq(st.runId);
        st.lastError = null;
        store.saveStage(st);
        audit.append(st.runId, "agent:" + st.activeAgent, "STAGE_SUCCEEDED", st.spec.id(),
                Map.of("agent", st.activeAgent, "activeMs", st.activeMs, "retries", st.retries));
    }

    // ================================================================= change handling

    /** Marks every stage that consumed a changed artifact as stale; they re-run, and unchanged outputs stop the ripple. */
    private void invalidateConsumers(UUID runId, Set<String> changed, String causeStage) {
        for (Stage s : store.stages(runId)) {
            if (s.spec.id().equals(causeStage) || s.status == StageStatus.PENDING || s.status == StageStatus.SKIPPED
                    || s.status == StageStatus.ROLLED_BACK || s.status == StageStatus.FAILED) continue;
            var hit = s.spec.consumes().stream().filter(changed::contains).toList();
            if (hit.isEmpty()) continue;
            resetStage(s, "upstream artifact changed: " + hit + " (from " + causeStage + ")");
        }
    }

    private void resetStage(Stage s, String reason) {
        s.epoch++;
        s.status = StageStatus.PENDING;
        s.attempt = 0;
        s.rejections = 0;
        s.feedback = null;
        s.lastError = null;
        s.finishedAt = null;
        s.completedSeq = null;
        s.activeAgent = s.spec.agent();
        store.cancelPending(s.runId, s.spec.id());
        store.saveStage(s);
        audit.append(s.runId, "system", "STAGE_INVALIDATED", s.spec.id(), Map.of("reason", reason));
        store.insertDecision(s.runId, s.spec.id(), "system", "Re-run " + s.spec.title(), reason, List.of());
    }

    /** Rebuilds the graph from the current requirement and applies the diff to the live run. */
    private void replanLocked(Run updated, String reason, String actor) {
        UUID runId = updated.id();
        RequirementModel model = analyzer.analyze(updated.requirement(), updated.mode());
        var violations = policy.checkRequirement(model);
        if (!violations.isEmpty()) {
            store.updateRun(updated);
            recordViolations(runId, null, violations);
            safeStopLocked(runId, "policy-engine", "Blocked on re-plan: " + PolicyEngine.feedback(violations), false);
            return;
        }
        List<NodeSpec> specs = planner.build(model);
        Map<String, NodeSpec> next = specs.stream().collect(Collectors.toMap(NodeSpec::id, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<String, Stage> old = store.stages(runId).stream().collect(Collectors.toMap(s -> s.spec.id(), s -> s));
        var added = new ArrayList<String>();
        var removed = new ArrayList<String>();
        var changed = new ArrayList<String>();

        int planVersion = updated.planVersion() + 1;
        store.updateRun(new Run(updated.id(), updated.title(), updated.requirement(), updated.requirementVersion(), updated.scenario(),
                updated.mode(), RunStatus.RUNNING, planVersion, updated.faults(), updated.stopReason(), updated.createdAt(), null));

        for (NodeSpec spec : specs) {
            Stage s = old.get(spec.id());
            if (s == null) {
                store.insertStage(newStage(runId, spec));
                added.add(spec.id());
                audit.append(runId, "system", "NODE_ADDED", spec.id(), Map.of("dependsOn", spec.dependsOn()));
            } else if (!s.spec.equals(spec)) {
                s.spec = spec;
                changed.add(spec.id());
                audit.append(runId, "system", "NODE_CHANGED", spec.id(), Map.of("dependsOn", spec.dependsOn(), "consumes", spec.consumes()));
                if (s.status == StageStatus.PENDING) store.saveStage(s);
                else if (s.status == StageStatus.SUCCEEDED || s.status == StageStatus.AWAITING_APPROVAL || s.status == StageStatus.RUNNING)
                    resetStage(s, "node definition changed by re-plan");
                else store.saveStage(s);
            }
        }
        for (Stage s : old.values()) {
            if (next.containsKey(s.spec.id()) || s.status == StageStatus.SKIPPED) continue;
            removed.add(s.spec.id());
            store.revokeByStage(runId, s.spec.id());
            store.cancelPending(runId, s.spec.id());
            s.epoch++;
            s.status = StageStatus.SKIPPED;
            s.finishedAt = now();
            store.saveStage(s);
            audit.append(runId, "system", "NODE_REMOVED", s.spec.id(), Map.of("reason", "no longer required by the requirement"));
        }
        // The requirement text itself changed, so intake always re-runs; downstream follows only if its output changes.
        Stage intake = store.stage(runId, "intake").orElse(null);
        if (intake != null && intake.status != StageStatus.PENDING) resetStage(intake, reason);

        audit.append(runId, actor, "REPLAN", null, Map.of("reason", reason, "fromPlan", updated.planVersion(), "toPlan", planVersion,
                "added", added, "removed", removed, "changed", changed));
        store.insertDecision(runId, null, actor, "Re-plan v" + planVersion + ": +" + added.size() + " -" + removed.size() + " ~" + changed.size(),
                reason + ". Added " + added + ", removed " + removed + ", changed " + changed
                        + ". Downstream stages re-run only if their inputs actually change.", List.of());
    }

    // ================================================================= failure, rollback, safe-stop

    private void failRunWithRollback(UUID runId, String reason) {
        audit.append(runId, "system", "ROLLBACK_STARTED", null, Map.of("reason", reason));
        int n = rollbackStages(runId);
        Run run = requireRun(runId);
        store.updateRun(new Run(run.id(), run.title(), run.requirement(), run.requirementVersion(), run.scenario(), run.mode(),
                RunStatus.FAILED_ROLLED_BACK, run.planVersion(), run.faults(), reason, run.createdAt(), now()));
        audit.append(runId, "system", "ROLLBACK_COMPLETED", null, Map.of("stagesRolledBack", n, "reason", reason));
        store.insertDecision(runId, null, "system", "Roll back the run", reason, List.of());
    }

    /** Compensates completed stages in reverse completion order; everything not started is skipped. */
    private int rollbackStages(UUID runId) {
        List<Stage> stages = store.stages(runId);
        var done = stages.stream().filter(s -> s.status == StageStatus.SUCCEEDED || s.status == StageStatus.AWAITING_APPROVAL)
                .sorted(Comparator.comparing((Stage s) -> s.completedSeq == null ? Long.MAX_VALUE : s.completedSeq).reversed()).toList();
        RequirementModel model = analyzer.analyze(requireRun(runId).requirement(), requireRun(runId).mode());
        int n = 0;
        for (Stage s : done) {
            Agent a = agents.get(s.activeAgent);
            if (a != null) {
                try {
                    a.rollback(new AgentContext(runId, s.spec, model, 0, Map.of(), null, 0));
                } catch (Exception e) {
                    audit.append(runId, "system", "ROLLBACK_HOOK_FAILED", s.spec.id(), Map.of("error", String.valueOf(e)));
                }
            }
            int revoked = store.revokeByStage(runId, s.spec.id());
            s.epoch++;
            s.status = StageStatus.ROLLED_BACK;
            store.saveStage(s);
            audit.append(runId, "system", "STAGE_ROLLED_BACK", s.spec.id(), Map.of("artifactsRevoked", revoked));
            n++;
        }
        for (Stage s : stages) {
            if (s.status == StageStatus.PENDING || s.status == StageStatus.RUNNING) {
                s.epoch++;
                s.status = StageStatus.SKIPPED;
                store.saveStage(s);
            }
        }
        store.cancelPending(runId, null);
        return n;
    }

    private void safeStopLocked(UUID runId, String actor, String reason, boolean rollback) {
        Run run = requireRun(runId);
        for (Stage s : store.stages(runId)) {
            if (s.status == StageStatus.RUNNING) {
                s.epoch++;
                s.status = StageStatus.PENDING;
                store.saveStage(s);
            }
        }
        store.cancelPending(runId, null);
        audit.append(runId, actor, "SAFE_STOP", null, Map.of("reason", reason, "rollback", rollback));
        if (rollback) {
            audit.append(runId, "system", "ROLLBACK_STARTED", null, Map.of("reason", reason));
            int n = rollbackStages(runId);
            audit.append(runId, "system", "ROLLBACK_COMPLETED", null, Map.of("stagesRolledBack", n, "reason", reason));
        }
        store.updateRun(new Run(run.id(), run.title(), run.requirement(), run.requirementVersion(), run.scenario(), run.mode(),
                RunStatus.SAFE_STOPPED, run.planVersion(), run.faults(), reason, run.createdAt(), now()));
        store.insertDecision(runId, null, actor, "Safe-stop the run", reason, List.of());
    }

    // ================================================================= helpers

    private void recordViolations(UUID runId, String stageId, List<Violation> violations) {
        for (Violation v : violations)
            audit.append(runId, "policy-engine", "POLICY_VIOLATION", stageId,
                    Map.of("rule", v.rule(), "category", v.category(), "severity", v.severity().name(), "message", v.message()));
    }

    private Map<String, Artifact> inputsFor(UUID runId, NodeSpec spec) {
        var map = new LinkedHashMap<String, Artifact>();
        for (String name : spec.consumes()) store.latestActive(runId, name).ifPresent(a -> map.put(name, a));
        return map;
    }

    private static List<String> versions(Map<String, Artifact> inputs) {
        return inputs.values().stream().map(a -> a.name() + "@" + a.version()).sorted().toList();
    }

    private Stage newStage(UUID runId, NodeSpec spec) {
        var s = new Stage();
        s.runId = runId;
        s.spec = spec;
        s.activeAgent = spec.agent();
        return s;
    }

    private Run requireRun(UUID id) {
        return store.run(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
    }

    private static Run withRequirement(Run r, String text) {
        return new Run(r.id(), r.title(), text, r.requirementVersion() + 1, r.scenario(), r.mode(), r.status(), r.planVersion(),
                r.faults(), r.stopReason(), r.createdAt(), r.finishedAt());
    }

    private <T> T locked(UUID id, Supplier<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            return tx.execute(status -> body.get());
        } finally {
            lock.unlock();
        }
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private static String orDefault(String s, String d) { return s == null || s.isBlank() ? d : s.strip(); }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Small formatter kept private to the engine. */
    private static final class Md2 {
        static String features(RequirementModel m) {
            return m.features().stream().map(Enum::name).collect(Collectors.joining(", "));
        }
    }
}
