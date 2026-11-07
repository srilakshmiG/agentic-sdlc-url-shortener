package com.agentic.orchestrator.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reliability metrics computed from the durable audit trail and stage records, not from counters that reset. */
@Service
public class MetricsService {

    public record StageTiming(String stage, double avgActiveMs, long samples) {}

    public record Metrics(long runsTotal, Map<String, Long> runsByStatus, double successRate, long stageAttempts,
                          long retries, long fallbacks, long rollbacks, double rollbackRate, long replans,
                          long policyViolations, long humanApprovals, long humanRejections, long safeStops,
                          Double mttrMs, long recoveredStages, Double e2eAvgMs, Double e2eP95Ms,
                          Double agentActiveAvgMs, Double humanWaitAvgMs, List<StageTiming> stageTimings,
                          String definitions) {}

    private final JdbcClient jdbc;

    public MetricsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    private Double scalar(String sql) {
        List<Double> rows = jdbc.sql(sql).query((rs, i) -> {
            double d = rs.getDouble(1);
            return rs.wasNull() ? null : d;
        }).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Metrics compute() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.sql("SELECT status, COUNT(*) c FROM runs GROUP BY status ORDER BY status")
                .query((rs, i) -> byStatus.put(rs.getString("status"), rs.getLong("c"))).list();
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long completed = byStatus.getOrDefault("COMPLETED", 0L);
        long terminal = completed + byStatus.getOrDefault("FAILED_ROLLED_BACK", 0L) + byStatus.getOrDefault("SAFE_STOPPED", 0L);

        Map<String, Long> events = new LinkedHashMap<>();
        jdbc.sql("SELECT type, COUNT(*) c FROM audit_events GROUP BY type")
                .query((rs, i) -> events.put(rs.getString("type"), rs.getLong("c"))).list();

        long rollbackRuns = jdbc.sql("SELECT COUNT(DISTINCT run_id) FROM audit_events WHERE type = 'ROLLBACK_COMPLETED'")
                .query(Long.class).single();
        Double mttr = scalar("""
                SELECT AVG(EXTRACT(EPOCH FROM (finished_at - first_failed_at)) * 1000)::float8 FROM stages
                WHERE first_failed_at IS NOT NULL AND status = 'SUCCEEDED' AND finished_at >= first_failed_at""");
        long recovered = jdbc.sql("SELECT COUNT(*) FROM stages WHERE first_failed_at IS NOT NULL AND status = 'SUCCEEDED'")
                .query(Long.class).single();

        Double[] e2e = {
                scalar("SELECT AVG(EXTRACT(EPOCH FROM (finished_at - created_at)) * 1000)::float8 FROM runs WHERE status = 'COMPLETED'"),
                scalar("""
                        SELECT (percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (finished_at - created_at)) * 1000))::float8
                        FROM runs WHERE status = 'COMPLETED'""")};
        Double active = scalar("""
                SELECT AVG(t.s)::float8 FROM (SELECT SUM(st.active_ms) s FROM stages st JOIN runs r ON r.id = st.run_id
                                              WHERE r.status = 'COMPLETED' GROUP BY st.run_id) t""");
        Double wait = e2e[0] == null || active == null ? null : Math.max(0, e2e[0] - active);

        var timings = jdbc.sql("""
                SELECT node_id, AVG(active_ms) a, COUNT(*) c FROM stages WHERE status = 'SUCCEEDED' AND active_ms > 0
                GROUP BY node_id ORDER BY a DESC""")
                .query((rs, i) -> new StageTiming(rs.getString("node_id"), rs.getDouble("a"), rs.getLong("c"))).list();

        return new Metrics(total, byStatus, terminal == 0 ? 0 : (double) completed / terminal,
                events.getOrDefault("STAGE_STARTED", 0L), events.getOrDefault("STAGE_RETRY", 0L),
                events.getOrDefault("FALLBACK_ENGAGED", 0L), rollbackRuns, total == 0 ? 0 : (double) rollbackRuns / total,
                events.getOrDefault("REPLAN", 0L), events.getOrDefault("POLICY_VIOLATION", 0L),
                events.getOrDefault("HUMAN_APPROVED", 0L), events.getOrDefault("HUMAN_REJECTED", 0L),
                events.getOrDefault("SAFE_STOP", 0L), mttr, recovered, e2e[0], e2e[1], active, wait, timings,
                "successRate = completed / terminal runs. MTTR = first failure to eventual success of the same stage. "
                        + "End-to-end latency includes time spent waiting for humans; agentActive is agent work only.");
    }
}
