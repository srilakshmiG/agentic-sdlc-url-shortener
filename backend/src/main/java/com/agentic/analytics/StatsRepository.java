package com.agentic.analytics;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatsRepository {

    public record DayCount(LocalDate date, long clicks) {}
    public record ReferrerCount(String referrer, long clicks) {}
    public record LinkStats(String code, long totalClicks, long uniqueVisitors, List<DayCount> last7Days,
                            List<ReferrerCount> topReferrers, String consistency) {}

    private final JdbcClient jdbc;

    public StatsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public LinkStats forLink(String code) {
        long total = jdbc.sql("SELECT click_count FROM links WHERE code = ?").param(code).query(Long.class).single();
        long uniques = jdbc.sql("SELECT COUNT(DISTINCT visitor_hash) FROM click_events WHERE code = ?")
                .param(code).query(Long.class).single();
        var days = jdbc.sql("""
                SELECT d::date AS day, COUNT(c.event_id) AS clicks
                FROM generate_series(current_date - 6, current_date, interval '1 day') d
                LEFT JOIN click_events c ON c.code = ? AND c.clicked_at::date = d::date
                GROUP BY d ORDER BY d""")
                .param(code)
                .query((rs, i) -> new DayCount(rs.getDate("day").toLocalDate(), rs.getLong("clicks"))).list();
        var refs = jdbc.sql("""
                SELECT referrer, COUNT(*) AS clicks FROM click_events WHERE code = ?
                GROUP BY referrer ORDER BY clicks DESC, referrer LIMIT 5""")
                .param(code)
                .query((rs, i) -> new ReferrerCount(rs.getString("referrer"), rs.getLong("clicks"))).list();
        return new LinkStats(code, total, uniques, days, refs, "eventual: clicks are aggregated asynchronously via Kafka");
    }
}
