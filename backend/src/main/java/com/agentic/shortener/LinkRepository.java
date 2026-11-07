package com.agentic.shortener;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Returns false when the code (or idempotency key) already exists. */
    public boolean insert(Link link, String idempotencyKey) {
        try {
            jdbc.sql("""
                    INSERT INTO links (code, target_url, created_at, expires_at, idempotency_key)
                    VALUES (?, ?, ?, ?::timestamptz, ?::varchar)""")
                    .param(link.code()).param(link.targetUrl()).param(Timestamp.from(link.createdAt()))
                    .param(link.expiresAt() == null ? null : Timestamp.from(link.expiresAt()))
                    .param(idempotencyKey)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Link> findByCode(String code) {
        return jdbc.sql("SELECT * FROM links WHERE code = ?").param(code).query(this::map).optional();
    }

    public Optional<Link> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM links WHERE idempotency_key = ?").param(key).query(this::map).optional();
    }

    public List<Link> recent(int limit) {
        return jdbc.sql("SELECT * FROM links ORDER BY created_at DESC LIMIT ?").param(limit).query(this::map).list();
    }

    public boolean disable(String code) {
        return jdbc.sql("UPDATE links SET disabled = TRUE WHERE code = ?").param(code).update() > 0;
    }

    private Link map(ResultSet rs, int row) throws SQLException {
        Timestamp exp = rs.getTimestamp("expires_at");
        return new Link(rs.getString("code"), rs.getString("target_url"),
                rs.getTimestamp("created_at").toInstant(), exp == null ? null : exp.toInstant(),
                rs.getBoolean("disabled"), rs.getLong("click_count"));
    }
}
