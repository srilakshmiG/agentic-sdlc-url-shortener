package com.agentic.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;

/**
 * Idempotent aggregator: the event id is the primary key, so a redelivered message inserts nothing
 * and does not bump the counter. Failures are retried, then routed to the dead-letter topic.
 */
@Component
public class ClickConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickConsumer.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ClickConsumer(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(topics = "${app.kafka.click-topic}")
    @Transactional
    public void onClick(String payload) {
        ClickEvent e = json.readValue(payload, ClickEvent.class);
        int inserted = jdbc.sql("""
                INSERT INTO click_events (event_id, code, clicked_at, referrer, visitor_hash)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING""")
                .param(e.eventId()).param(e.code()).param(Timestamp.from(e.clickedAt()))
                .param(e.referrer()).param(e.visitorHash()).update();
        if (inserted == 1) {
            jdbc.sql("UPDATE links SET click_count = click_count + 1 WHERE code = ?").param(e.code()).update();
        } else {
            log.debug("Duplicate click event {} ignored", e.eventId());
        }
    }
}
