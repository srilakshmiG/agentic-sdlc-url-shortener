package com.agentic.it;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.rate-limit.creates-per-minute=5")
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
class RateLimitIT {

    @Value("${local.server.port}") int port;

    @Test
    void creationIsLimitedPerClientWithRetryAfter() throws Exception {
        var http = HttpClient.newHttpClient();
        int created = 0;
        HttpResponse<String> limited = null;
        for (int i = 0; i < 8 && limited == null; i++) {
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/links"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/rl\",\"alias\":\"rl-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) limited = res; else if (res.statusCode() == 201) created++;
        }
        assertNotNull(limited, "the sixth request in a minute should be limited");
        assertEquals(5, created);
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertTrue(limited.body().contains("Too Many Requests"));
    }
}
