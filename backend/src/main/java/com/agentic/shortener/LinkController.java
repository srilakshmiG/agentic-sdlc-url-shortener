package com.agentic.shortener;

import com.agentic.analytics.StatsRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService service;
    private final StatsRepository stats;
    private final String baseUrl;

    public LinkController(LinkService service, StatsRepository stats,
                          @Value("${app.public-base-url}") String baseUrl) {
        this.service = service;
        this.stats = stats;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest req,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && key.length() > 80) throw new LinkService.BadRequestException("Idempotency-Key is too long");
        var created = service.create(req, key);
        var body = LinkResponse.of(created.link(), baseUrl);
        var location = URI.create("/api/v1/links/" + created.link().code());
        return created.replayed() ? ResponseEntity.ok().location(location).body(body)
                : ResponseEntity.created(location).body(body);
    }

    @GetMapping
    public List<LinkResponse> list() {
        return service.recent().stream().map(l -> LinkResponse.of(l, baseUrl)).toList();
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return LinkResponse.of(service.get(code), baseUrl);
    }

    @GetMapping("/{code}/stats")
    public StatsRepository.LinkStats stats(@PathVariable String code) {
        service.get(code);
        return stats.forLink(code);
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> disable(@PathVariable String code) {
        service.disable(code);
        return ResponseEntity.noContent().build();
    }
}
