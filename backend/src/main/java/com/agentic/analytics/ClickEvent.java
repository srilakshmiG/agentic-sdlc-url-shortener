package com.agentic.analytics;

import java.time.Instant;
import java.util.UUID;

/** Event contract on topic link.clicks. Raw IP is never carried: only a one-way visitor hash. */
public record ClickEvent(UUID eventId, String code, Instant clickedAt, String referrer, String visitorHash) {
}
