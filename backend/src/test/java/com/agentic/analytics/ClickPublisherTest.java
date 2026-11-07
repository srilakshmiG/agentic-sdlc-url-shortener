package com.agentic.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

class ClickPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void brokerFailuresAreCountedAndNeverThrown() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("broker down"));
        var meters = new SimpleMeterRegistry();
        var publisher = new ClickPublisher(kafka, JsonMapper.builder().build(), Clock.systemUTC(), meters, "link.clicks");
        assertDoesNotThrow(() -> publisher.send(new ClickEvent(UUID.randomUUID(), "abc", Instant.now(), "direct", "h")));
        assertEquals(1.0, meters.counter("shortener.click.publish.failed").count());
    }

    @Test
    void referrerIsReducedToAHostAndVisitorsAreHashed() {
        assertEquals("example.org", ClickPublisher.referrerHost("https://example.org/a/b?c=d"));
        assertEquals("direct", ClickPublisher.referrerHost(null));
        assertEquals("direct", ClickPublisher.referrerHost("::::"));
        String h = ClickPublisher.visitorHash("203.0.113.9", "Mozilla/5.0");
        assertEquals(16, h.length());
        assertFalse(h.contains("203"));
        assertEquals(h, ClickPublisher.visitorHash("203.0.113.9", "Mozilla/5.0"));
        assertNotEquals(h, ClickPublisher.visitorHash("203.0.113.10", "Mozilla/5.0"));
    }
}
