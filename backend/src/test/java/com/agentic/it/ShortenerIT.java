package com.agentic.it;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The shortener over real HTTP, PostgreSQL and Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
class ShortenerIT {

    @Value("${local.server.port}") int port;
    @Value("${spring.kafka.bootstrap-servers}") String bootstrap;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String code() { return "it-" + UUID.randomUUID().toString().substring(0, 8); }

    private void await(String what, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + what);
    }

    private long clicks(String code) {
        return jdbc.sql("SELECT click_count FROM links WHERE code = ?").param(code).query(Long.class).single();
    }

    @Test
    void createRedirectAndCountClicksThroughKafka() throws Exception {
        String c = code();
        var created = send("POST", "/api/v1/links", "{\"url\":\"https://example.com/it\",\"alias\":\"" + c + "\"}");
        assertEquals(201, created.statusCode());
        for (int i = 0; i < 3; i++) {
            var r = send("GET", "/" + c, null, "Referer", "https://news.example.org/p");
            assertEquals(302, r.statusCode());
            assertEquals("https://example.com/it", r.headers().firstValue("Location").orElseThrow());
        }
        await("3 clicks aggregated", () -> clicks(c) == 3);
        var stats = send("GET", "/api/v1/links/" + c + "/stats", null);
        assertEquals(200, stats.statusCode());
        assertTrue(stats.body().contains("news.example.org"));
    }

    @Test
    void duplicateDeliveryNeverDoubleCounts() throws Exception {
        String c = code();
        send("POST", "/api/v1/links", "{\"url\":\"https://example.com/dup\",\"alias\":\"" + c + "\"}");
        var event = json.writeValueAsString(new com.agentic.analytics.ClickEvent(UUID.randomUUID(), c, Instant.now(), "direct", "abcd"));
        kafka.send("link.clicks", c, event).get();
        kafka.send("link.clicks", c, event).get();
        await("first delivery counted", () -> clicks(c) == 1);
        Thread.sleep(1500);
        assertEquals(1, clicks(c), "the replayed event id must be ignored");
    }

    @Test
    void poisonMessageGoesToTheDeadLetterTopicAndDoesNotBlockTheConsumer() throws Exception {
        String marker = "poison-" + UUID.randomUUID();
        kafka.send("link.clicks", "x", marker).get();
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        boolean found = false;
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(java.util.List.of("link.clicks.dlt"));
            long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!found && System.nanoTime() < end)
                for (var rec : consumer.poll(Duration.ofMillis(500))) if (marker.equals(rec.value())) found = true;
        }
        assertTrue(found, "poison record should be parked on link.clicks.dlt");
        String c = code();
        send("POST", "/api/v1/links", "{\"url\":\"https://example.com/after\",\"alias\":\"" + c + "\"}");
        send("GET", "/" + c, null);
        await("consumer still healthy after the poison message", () -> clicks(c) == 1);
    }

    @Test
    void validationAndErrorContract() throws Exception {
        assertEquals(400, send("POST", "/api/v1/links", "{\"url\":\"http://localhost/x\"}").statusCode());
        assertEquals(400, send("POST", "/api/v1/links", "{\"url\":\"javascript:alert(1)\"}").statusCode());
        assertEquals(400, send("POST", "/api/v1/links", "{\"url\":\"\"}").statusCode());
        assertEquals(400, send("POST", "/api/v1/links", "{\"url\":\"https://e.com\",\"alias\":\"api\"}").statusCode());
        assertEquals(400, send("POST", "/api/v1/links", "{\"url\":\"https://e.com\",\"expiresAt\":\"2001-01-01T00:00:00Z\"}").statusCode());
        var bad = send("POST", "/api/v1/links", "{\"url\":\"http://127.0.0.1\"}");
        assertTrue(bad.headers().firstValue("Content-Type").orElse("").contains("problem+json"));
        assertEquals(404, send("GET", "/nope-" + code(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/links/missing-code", null).statusCode());
    }

    @Test
    void aliasConflictAndIdempotentReplay() throws Exception {
        String c = code();
        String body = "{\"url\":\"https://example.com/a\",\"alias\":\"" + c + "\"}";
        assertEquals(201, send("POST", "/api/v1/links", body).statusCode());
        assertEquals(409, send("POST", "/api/v1/links", body).statusCode());

        String key = UUID.randomUUID().toString();
        var first = send("POST", "/api/v1/links", "{\"url\":\"https://example.com/b\"}", "Idempotency-Key", key);
        var second = send("POST", "/api/v1/links", "{\"url\":\"https://example.com/b\"}", "Idempotency-Key", key);
        assertEquals(201, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(json.readTree(first.body()).get("code").asString(), json.readTree(second.body()).get("code").asString());
    }

    @Test
    void expiredAndDisabledLinksReturnGoneAndAreNotCounted() throws Exception {
        String c = code();
        String soon = Instant.now().plusMillis(1500).toString();
        assertEquals(201, send("POST", "/api/v1/links", "{\"url\":\"https://example.com/e\",\"alias\":\"" + c + "\",\"expiresAt\":\"" + soon + "\"}").statusCode());
        assertEquals(302, send("GET", "/" + c, null).statusCode());
        Thread.sleep(2000);
        assertEquals(410, send("GET", "/" + c, null).statusCode());

        String d = code();
        send("POST", "/api/v1/links", "{\"url\":\"https://example.com/d\",\"alias\":\"" + d + "\"}");
        assertEquals(204, send("DELETE", "/api/v1/links/" + d, null).statusCode());
        assertEquals(410, send("GET", "/" + d, null).statusCode());
        await("only the one live click counted", () -> clicks(c) == 1);
        Thread.sleep(1000);
        assertEquals(1, clicks(c));
        assertEquals(0, clicks(d));
    }
}
