package com.agentic.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes click events without ever affecting the redirect: the send runs on a virtual thread
 * and any failure is counted and logged. Analytics is best effort, redirects are not.
 */
@Component
public class ClickPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Clock clock;
    private final String topic;
    private final Counter published;
    private final Counter failed;

    public ClickPublisher(KafkaTemplate<String, String> kafka, JsonMapper json, Clock clock,
                          MeterRegistry meters, @Value("${app.kafka.click-topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.clock = clock;
        this.topic = topic;
        this.published = meters.counter("shortener.click.published");
        this.failed = meters.counter("shortener.click.publish.failed");
    }

    public void publish(String code, HttpServletRequest request) {
        var event = new ClickEvent(UUID.randomUUID(), code, clock.instant(),
                referrerHost(request.getHeader("Referer")),
                visitorHash(request.getRemoteAddr(), request.getHeader("User-Agent")));
        Thread.startVirtualThread(() -> send(event));
    }

    void send(ClickEvent event) {
        try {
            kafka.send(topic, event.code(), json.writeValueAsString(event)).whenComplete((r, ex) -> {
                if (ex != null) onFailure(event, ex); else published.increment();
            });
        } catch (Exception e) {
            onFailure(event, e);
        }
    }

    private void onFailure(ClickEvent event, Throwable ex) {
        failed.increment();
        log.warn("Click event {} for {} dropped: {}", event.eventId(), event.code(), ex.toString());
    }

    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) return "direct";
        try {
            String host = URI.create(referer).getHost();
            return host == null ? "direct" : host.length() > 200 ? host.substring(0, 200) : host;
        } catch (IllegalArgumentException e) {
            return "direct";
        }
    }

    static String visitorHash(String ip, String userAgent) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(((ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
