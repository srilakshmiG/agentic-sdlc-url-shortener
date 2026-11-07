package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.model.Model.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only, hash-chained audit trail: each event commits to the previous one, so any edit,
 * deletion or reordering of a run's history is detectable by {@link #verify}.
 */
@Component
public class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    public record Verification(boolean valid, int events, Long brokenAtSeq, String reason) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Callers hold the per-run lock, so the chain of one run is written by one thread at a time. */
    public AuditEvent append(UUID runId, String actor, String type, String stageId, Map<String, ?> payload) {
        String body = json.writeValueAsString(payload == null ? Map.of() : payload);
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String prev = jdbc.sql("SELECT hash FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1")
                .param(runId).query(String.class).optional().orElse(GENESIS);
        String hash = hash(prev, runId, ts, actor, type, stageId, body);
        long seq = jdbc.sql("""
                INSERT INTO audit_events (run_id, ts, actor, type, stage_id, payload, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?::varchar, ?, ?, ?) RETURNING seq""")
                .param(runId).param(Timestamp.from(ts)).param(actor).param(type)
                .param(stageId).param(body).param(prev).param(hash)
                .query(Long.class).single();
        return new AuditEvent(seq, runId, ts, actor, type, stageId, body, prev, hash);
    }

    public List<AuditEvent> events(UUID runId) {
        return jdbc.sql("SELECT * FROM audit_events WHERE run_id = ? ORDER BY seq").param(runId)
                .query((rs, i) -> new AuditEvent(rs.getLong("seq"), runId, rs.getTimestamp("ts").toInstant(),
                        rs.getString("actor"), rs.getString("type"), rs.getString("stage_id"),
                        rs.getString("payload"), rs.getString("prev_hash"), rs.getString("hash"))).list();
    }

    public Verification verify(UUID runId) {
        return verify(events(runId));
    }

    public static Verification verify(List<AuditEvent> events) {
        String prev = GENESIS;
        for (AuditEvent e : events) {
            if (!e.prevHash().equals(prev))
                return new Verification(false, events.size(), e.seq(), "previous hash does not match: an event was removed or reordered");
            String expected = hash(prev, e.runId(), e.ts(), e.actor(), e.type(), e.stageId(), e.payload());
            if (!expected.equals(e.hash()))
                return new Verification(false, events.size(), e.seq(), "content hash does not match: the event was altered");
            prev = e.hash();
        }
        return new Verification(true, events.size(), null, "chain intact");
    }

    static String hash(String prev, UUID runId, Instant ts, String actor, String type, String stageId, String payload) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            String material = String.join("\u001f", prev, runId.toString(), ts.toString(), actor, type,
                    stageId == null ? "" : stageId, payload);
            return HexFormat.of().formatHex(md.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
