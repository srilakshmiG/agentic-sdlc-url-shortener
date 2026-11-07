package com.agentic.orchestrator.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Domain types of the orchestration layer. State is durable in PostgreSQL; nothing lives only in memory. */
public final class Model {

    private Model() {}

    public enum StageStatus { PENDING, RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, ROLLED_BACK, SKIPPED }

    public enum RunStatus {
        RUNNING, AWAITING_HUMAN, COMPLETED, FAILED_ROLLED_BACK, SAFE_STOPPED;

        public boolean terminal() { return this == COMPLETED || this == FAILED_ROLLED_BACK || this == SAFE_STOPPED; }
    }

    public enum ApprovalKind { APPROVAL, CLARIFICATION }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, CANCELLED }

    /** Fault injection used to demonstrate retries, fallback, rollback and guardrails on demand. */
    public enum FaultMode { TRANSIENT, PRIMARY_DOWN, FATAL, ROGUE_ACTION }

    public enum Severity { SOFT, HARD }

    /** One node of the dependency graph. Equality of two specs decides whether a re-plan changes a node. */
    public record NodeSpec(String id, String title, String agent, List<String> dependsOn, List<String> consumes,
                           List<String> produces, boolean highImpact, int maxRetries, String fallbackAgent,
                           String lane) {
        public boolean needsApproval() { return highImpact; }
    }

    public record Fault(String stage, FaultMode mode, int count) {}

    public record Run(UUID id, String title, String requirement, int requirementVersion, String scenario,
                      String mode, RunStatus status, int planVersion, List<Fault> faults, String stopReason,
                      Instant createdAt, Instant finishedAt) {}

    /** Mutable runtime state of a node. Only the engine mutates it, always under the run lock. */
    public static class Stage {
        public UUID runId;
        public NodeSpec spec;
        public String activeAgent;
        public StageStatus status = StageStatus.PENDING;
        public int attempt;
        public int retries;
        public int rejections;
        public int epoch;
        public Instant startedAt;
        public Instant finishedAt;
        public Instant firstFailedAt;
        public long activeMs;
        public String lastError;
        public String feedback;
        public Long completedSeq;
    }

    public record Artifact(long id, UUID runId, String name, int version, String stageId, String contentType,
                           String content, String contentHash, List<String> derivedFrom, String status,
                           Instant createdAt) {}

    public record Question(String slot, String text, String assumption) {}

    public record Approval(UUID id, UUID runId, String stageId, ApprovalKind kind, ApprovalStatus status,
                           String summary, List<Question> questions, Map<String, String> answers,
                           String decidedBy, String comment, Instant createdAt, Instant decidedAt) {}

    public record Decision(long id, UUID runId, String stageId, String actor, String decision, String rationale,
                           List<String> inputs, Instant createdAt) {}

    public record AuditEvent(long seq, UUID runId, Instant ts, String actor, String type, String stageId,
                             String payload, String prevHash, String hash) {}
}
