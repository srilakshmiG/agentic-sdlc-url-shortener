package com.agentic.shortener;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @Size(min = 3, max = 32) String alias,
        Instant expiresAt) {
}
