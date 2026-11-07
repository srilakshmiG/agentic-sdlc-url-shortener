package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.model.Model.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** All persistence for runs, stages, artifacts, approvals and decisions. */
@Repository
public class RunStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RunStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- runs -------------------------------------------------------------------------------

    public void insertRun(Run r) {
        jdbc.sql("""
                INSERT INTO runs (id, title, requirement, requirement_version, scenario, mode, status, plan_version,
                                  faults, stop_reason, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text, ?, ?::timestamptz)""")
                .param(r.id()).param(r.title()).param(r.requirement()).param(r.requirementVersion()).param(r.scenario())
                .param(r.mode()).param(r.status().name()).param(r.planVersion()).param(write(r.faults()))
                .param(r.stopReason()).param(ts(r.createdAt()))
                .param(r.finishedAt() == null ? null : ts(r.finishedAt())).update();
    }

    public void updateRun(Run r) {
        jdbc.sql("""
                UPDATE runs SET title = ?, requirement = ?, requirement_version = ?, status = ?, plan_version = ?,
                                stop_reason = ?::text, finished_at = ?::timestamptz WHERE id = ?""")
                .param(r.title()).param(r.requirement()).param(r.requirementVersion()).param(r.status().name())
                .param(r.planVersion()).param(r.stopReason())
                .param(r.finishedAt() == null ? null : ts(r.finishedAt())).param(r.id()).update();
    }

    public Optional<Run> run(UUID id) {
        return jdbc.sql("SELECT * FROM runs WHERE id = ?").param(id).query(this::mapRun).optional();
    }

    public List<Run> runs() {
        return jdbc.sql("SELECT * FROM runs ORDER BY created_at DESC LIMIT 100").query(this::mapRun).list();
    }

    public List<Run> runsWithStatus(RunStatus status) {
        return jdbc.sql("SELECT * FROM runs WHERE status = ?").param(status.name()).query(this::mapRun).list();
    }

    // ---- stages -----------------------------------------------------------------------------

    public void insertStage(Stage s) {
        jdbc.sql("INSERT INTO stages (run_id, node_id, spec, active_agent, status) VALUES (?, ?, ?, ?, ?)")
                .param(s.runId).param(s.spec.id()).param(write(s.spec)).param(s.activeAgent).param(s.status.name()).update();
    }

    public void saveStage(Stage s) {
        jdbc.sql("""
                UPDATE stages SET spec = ?, active_agent = ?, status = ?, attempt = ?, retries = ?, rejections = ?,
                    epoch = ?, started_at = ?::timestamptz, finished_at = ?::timestamptz, first_failed_at = ?::timestamptz, active_ms = ?, last_error = ?::text,
                    feedback = ?::text, completed_seq = ?::bigint WHERE run_id = ? AND node_id = ?""")
                .param(write(s.spec)).param(s.activeAgent).param(s.status.name()).param(s.attempt).param(s.retries)
                .param(s.rejections).param(s.epoch).param(tsOrNull(s.startedAt))
                .param(tsOrNull(s.finishedAt))
                .param(tsOrNull(s.firstFailedAt)).param(s.activeMs)
                .param(s.lastError).param(s.feedback)
                .param(s.completedSeq).param(s.runId).param(s.spec.id()).update();
    }

    public List<Stage> stages(UUID runId) {
        return jdbc.sql("SELECT * FROM stages WHERE run_id = ? ORDER BY node_id").param(runId).query(this::mapStage).list();
    }

    public Optional<Stage> stage(UUID runId, String nodeId) {
        return jdbc.sql("SELECT * FROM stages WHERE run_id = ? AND node_id = ?").param(runId).param(nodeId)
                .query(this::mapStage).optional();
    }

    public long nextCompletionSeq(UUID runId) {
        return jdbc.sql("SELECT COALESCE(MAX(completed_seq), 0) + 1 FROM stages WHERE run_id = ?").param(runId)
                .query(Long.class).single();
    }

    // ---- artifacts --------------------------------------------------------------------------

    public Optional<Artifact> latestActive(UUID runId, String name) {
        return jdbc.sql("SELECT * FROM artifacts WHERE run_id = ? AND name = ? AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1")
                .param(runId).param(name).query(this::mapArtifact).optional();
    }

    public int maxVersion(UUID runId, String name) {
        return jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM artifacts WHERE run_id = ? AND name = ?")
                .param(runId).param(name).query(Integer.class).single();
    }

    public void insertArtifact(Artifact a) {
        jdbc.sql("""
                INSERT INTO artifacts (run_id, name, version, stage_id, content_type, content, content_hash, derived_from, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .param(a.runId()).param(a.name()).param(a.version()).param(a.stageId()).param(a.contentType())
                .param(a.content()).param(a.contentHash()).param(write(a.derivedFrom())).param(a.status())
                .param(ts(a.createdAt())).update();
    }

    public void supersede(UUID runId, String name) {
        jdbc.sql("UPDATE artifacts SET status = 'SUPERSEDED' WHERE run_id = ? AND name = ? AND status = 'ACTIVE'")
                .param(runId).param(name).update();
    }

    public int revokeByStage(UUID runId, String stageId) {
        return jdbc.sql("UPDATE artifacts SET status = 'REVOKED' WHERE run_id = ? AND stage_id = ? AND status = 'ACTIVE'")
                .param(runId).param(stageId).update();
    }

    public List<Artifact> artifacts(UUID runId) {
        return jdbc.sql("SELECT * FROM artifacts WHERE run_id = ? ORDER BY name, version").param(runId)
                .query(this::mapArtifact).list();
    }

    public Optional<Artifact> artifact(UUID runId, String name, Integer version) {
        return version == null
                ? jdbc.sql("SELECT * FROM artifacts WHERE run_id = ? AND name = ? ORDER BY version DESC LIMIT 1")
                        .param(runId).param(name).query(this::mapArtifact).optional()
                : jdbc.sql("SELECT * FROM artifacts WHERE run_id = ? AND name = ? AND version = ?")
                        .param(runId).param(name).param(version).query(this::mapArtifact).optional();
    }

    // ---- approvals --------------------------------------------------------------------------

    public void insertApproval(Approval a) {
        jdbc.sql("""
                INSERT INTO approvals (id, run_id, stage_id, kind, status, summary, questions, answers, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .param(a.id()).param(a.runId()).param(a.stageId()).param(a.kind().name()).param(a.status().name())
                .param(a.summary()).param(write(a.questions())).param(write(a.answers())).param(ts(a.createdAt())).update();
    }

    public void decideApproval(UUID id, ApprovalStatus status, String by, String comment, Map<String, String> answers) {
        jdbc.sql("UPDATE approvals SET status = ?, decided_by = ?::varchar, comment = ?::text, answers = ?, decided_at = ? WHERE id = ?")
                .param(status.name()).param(by).param(comment)
                .param(write(answers)).param(ts(Instant.now())).param(id).update();
    }

    public int cancelPending(UUID runId, String stageId) {
        return jdbc.sql("""
                UPDATE approvals SET status = 'CANCELLED', decided_at = now()
                WHERE run_id = ? AND status = 'PENDING' AND (?::text IS NULL OR stage_id = ?::text)""")
                .param(runId).param(stageId).param(stageId).update();
    }

    public Optional<Approval> approval(UUID id) {
        return jdbc.sql("SELECT * FROM approvals WHERE id = ?").param(id).query(this::mapApproval).optional();
    }

    public List<Approval> approvals(UUID runId) {
        return jdbc.sql("SELECT * FROM approvals WHERE run_id = ? ORDER BY created_at").param(runId)
                .query(this::mapApproval).list();
    }

    public boolean hasApproved(UUID runId, String stageId) {
        return jdbc.sql("SELECT COUNT(*) FROM approvals WHERE run_id = ? AND stage_id = ? AND status = 'APPROVED' AND kind = 'APPROVAL'")
                .param(runId).param(stageId).query(Long.class).single() > 0;
    }

    // ---- decisions --------------------------------------------------------------------------

    public void insertDecision(UUID runId, String stageId, String actor, String decision, String rationale, List<String> inputs) {
        jdbc.sql("INSERT INTO decisions (run_id, stage_id, actor, decision, rationale, inputs, created_at) VALUES (?, ?::varchar, ?, ?, ?, ?, ?)")
                .param(runId).param(stageId).param(actor).param(decision).param(rationale)
                .param(write(inputs)).param(ts(Instant.now())).update();
    }

    public List<Decision> decisions(UUID runId) {
        return jdbc.sql("SELECT * FROM decisions WHERE run_id = ? ORDER BY id").param(runId)
                .query((rs, i) -> new Decision(rs.getLong("id"), runId, rs.getString("stage_id"), rs.getString("actor"),
                        rs.getString("decision"), rs.getString("rationale"),
                        read(rs.getString("inputs"), new TypeReference<List<String>>() {}),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    // ---- mapping ----------------------------------------------------------------------------

    private Run mapRun(ResultSet rs, int i) throws SQLException {
        return new Run(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("requirement"),
                rs.getInt("requirement_version"), rs.getString("scenario"), rs.getString("mode"),
                RunStatus.valueOf(rs.getString("status")), rs.getInt("plan_version"),
                read(rs.getString("faults"), new TypeReference<List<Fault>>() {}), rs.getString("stop_reason"),
                rs.getTimestamp("created_at").toInstant(), inst(rs.getTimestamp("finished_at")));
    }

    private Stage mapStage(ResultSet rs, int i) throws SQLException {
        var s = new Stage();
        s.runId = rs.getObject("run_id", UUID.class);
        s.spec = read(rs.getString("spec"), new TypeReference<NodeSpec>() {});
        s.activeAgent = rs.getString("active_agent");
        s.status = StageStatus.valueOf(rs.getString("status"));
        s.attempt = rs.getInt("attempt");
        s.retries = rs.getInt("retries");
        s.rejections = rs.getInt("rejections");
        s.epoch = rs.getInt("epoch");
        s.startedAt = inst(rs.getTimestamp("started_at"));
        s.finishedAt = inst(rs.getTimestamp("finished_at"));
        s.firstFailedAt = inst(rs.getTimestamp("first_failed_at"));
        s.activeMs = rs.getLong("active_ms");
        s.lastError = rs.getString("last_error");
        s.feedback = rs.getString("feedback");
        long seq = rs.getLong("completed_seq");
        s.completedSeq = rs.wasNull() ? null : seq;
        return s;
    }

    private Artifact mapArtifact(ResultSet rs, int i) throws SQLException {
        return new Artifact(rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("name"), rs.getInt("version"),
                rs.getString("stage_id"), rs.getString("content_type"), rs.getString("content"), rs.getString("content_hash"),
                read(rs.getString("derived_from"), new TypeReference<List<String>>() {}), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Approval mapApproval(ResultSet rs, int i) throws SQLException {
        return new Approval(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getString("stage_id"),
                ApprovalKind.valueOf(rs.getString("kind")), ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("summary"), read(rs.getString("questions"), new TypeReference<List<Question>>() {}),
                read(rs.getString("answers"), new TypeReference<LinkedHashMap<String, String>>() {}),
                rs.getString("decided_by"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant(),
                inst(rs.getTimestamp("decided_at")));
    }

    private static Timestamp ts(Instant i) { return Timestamp.from(i); }

    private static Timestamp tsOrNull(Instant i) { return i == null ? null : Timestamp.from(i); }

    private static Instant inst(Timestamp t) { return t == null ? null : t.toInstant(); }

    private String write(Object o) { return json.writeValueAsString(o); }

    private <T> T read(String s, TypeReference<T> type) { return json.readValue(s, type); }
}
