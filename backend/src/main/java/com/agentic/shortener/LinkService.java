package com.agentic.shortener;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LinkService {

    private static final int MAX_CODE_ATTEMPTS = 5;

    private final LinkRepository repo;
    private final UrlValidator validator;
    private final CodeGenerator codes;
    private final Clock clock;

    public LinkService(LinkRepository repo, UrlValidator validator, CodeGenerator codes, Clock clock) {
        this.repo = repo;
        this.validator = validator;
        this.codes = codes;
        this.clock = clock;
    }

    public record Created(Link link, boolean replayed) {}

    public Created create(CreateLinkRequest req, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return new Created(existing.get(), true);
        }
        String target = validator.validate(req.url());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (req.expiresAt() != null && !req.expiresAt().isAfter(now))
            throw new BadRequestException("expiresAt must be in the future");

        if (req.alias() != null && !req.alias().isBlank()) {
            if (!codes.isValidAlias(req.alias()))
                throw new BadRequestException("Alias must be 3-32 chars of letters, digits, '-' or '_' and not reserved");
            var link = new Link(req.alias(), target, now, req.expiresAt(), false, 0);
            if (!repo.insert(link, idempotencyKey)) {
                if (idempotencyKey != null) {
                    var winner = repo.findByIdempotencyKey(idempotencyKey);
                    if (winner.isPresent()) return new Created(winner.get(), true);
                }
                throw new ConflictException("Alias is already taken");
            }
            return new Created(link, false);
        }
        for (int i = 0; i < MAX_CODE_ATTEMPTS; i++) {
            var link = new Link(codes.next(), target, now, req.expiresAt(), false, 0);
            if (repo.insert(link, idempotencyKey)) return new Created(link, false);
            // an idempotency-key race returns the winner instead of a duplicate
            if (idempotencyKey != null) {
                var winner = repo.findByIdempotencyKey(idempotencyKey);
                if (winner.isPresent()) return new Created(winner.get(), true);
            }
        }
        throw new IllegalStateException("Could not allocate a unique code");
    }

    public Link get(String code) {
        return repo.findByCode(code).orElseThrow(() -> new NotFoundException("Link not found"));
    }

    public List<Link> recent() { return repo.recent(50); }

    public void disable(String code) {
        if (!repo.disable(code)) throw new NotFoundException("Link not found");
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String m) { super(m); }
    }
}
