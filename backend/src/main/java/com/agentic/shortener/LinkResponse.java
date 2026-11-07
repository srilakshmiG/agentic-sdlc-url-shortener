package com.agentic.shortener;

import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt,
                           Instant expiresAt, boolean disabled, long clickCount) {

    static LinkResponse of(Link l, String baseUrl) {
        return new LinkResponse(l.code(), baseUrl + "/" + l.code(), l.targetUrl(), l.createdAt(),
                l.expiresAt(), l.disabled(), l.clickCount());
    }
}
