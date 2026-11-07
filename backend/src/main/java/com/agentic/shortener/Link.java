package com.agentic.shortener;

import java.time.Instant;

public record Link(String code, String targetUrl, Instant createdAt, Instant expiresAt,
                   boolean disabled, long clickCount) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
