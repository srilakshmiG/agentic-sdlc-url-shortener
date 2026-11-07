package com.agentic.shortener;

import com.agentic.analytics.ClickPublisher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final LinkRepository repo;
    private final ClickPublisher clicks;
    private final Clock clock;

    public RedirectController(LinkRepository repo, ClickPublisher clicks, Clock clock) {
        this.repo = repo;
        this.clicks = clicks;
        this.clock = clock;
    }

    /** 302 (not 301) so every click reaches us and expiry or disable take effect immediately. */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        var link = repo.findByCode(code).orElse(null);
        if (link == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (link.disabled() || link.isExpired(clock.instant())) return ResponseEntity.status(HttpStatus.GONE).build();
        clicks.publish(code, request);   // never blocks or fails the redirect
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.targetUrl())).build();
    }
}
