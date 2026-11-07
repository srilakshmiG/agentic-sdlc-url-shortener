package com.agentic.orchestrator.model;

import java.util.List;

/**
 * Knowledge base of URL-shortener capabilities. Agents project this catalogue onto a requirement, which
 * keeps requirements, tasks, tests, risks and threats traceable to one source of truth.
 */
public enum Feature {

    SHORTEN("Create short links",
            List.of("shorten", "short link", "short url", "url shortener", "create-link", "create link"),
            "Users can submit a long URL and receive a short link.",
            List.of("A valid http(s) URL returns 201 with a unique short code", "Invalid or unsafe URLs return 400 with a problem detail"),
            List.of("POST /api/v1/links", "GET /api/v1/links/{code}"),
            List.of("links(code, target_url, created_at)"),
            List.of("Implement URL validation and scheme allowlist", "Implement collision-safe code generation", "Implement create-link endpoint"),
            List.of("rejects javascript: and file: schemes", "rejects private and loopback hosts", "retries on code collision"),
            List.of("Code collisions under load", "Open redirect abuse through unsafe targets"),
            List.of("Phishing or malware links hosted behind the short domain", "SSRF-style targets such as internal hosts"),
            List.of("UrlValidator", "CodeGenerator")),

    REDIRECT("Redirect short links",
            List.of("redirect", "open the link", "resolve"),
            "Opening a short link redirects the visitor to the target URL.",
            List.of("Known code returns 302 with Location", "Unknown code returns 404; expired or disabled returns 410"),
            List.of("GET /{code}"),
            List.of(),
            List.of("Implement redirect controller", "Choose 302 so every click is observable"),
            List.of("302 for active link", "404 for unknown code", "410 for disabled link"),
            List.of("301 caching would hide clicks and defeat expiry"),
            List.of("Header injection through crafted targets"),
            List.of("RedirectController", "GONE")),

    CUSTOM_ALIAS("Custom aliases",
            List.of("alias", "vanity", "custom code", "custom short"),
            "Users can choose their own short code instead of a generated one.",
            List.of("A free alias is accepted; a taken alias returns 409", "Reserved words and malformed aliases return 400"),
            List.of("POST /api/v1/links (adds optional alias)"),
            List.of("links.code reused as alias, unique by primary key"),
            List.of("Add alias validation and reserved-word list", "Return 409 on alias conflict"),
            List.of("alias conflict returns 409", "reserved alias returns 400"),
            List.of("Alias squatting and impersonation of brand names"),
            List.of("Alias used to mimic trusted paths such as /login or /admin"),
            List.of("alias", "RESERVED")),

    EXPIRY("Link expiry",
            List.of("expire", "expiry", "expiration", "ttl", "time-limited"),
            "Links can have an expiry after which they stop redirecting.",
            List.of("A future expiresAt is accepted; a past value returns 400", "Expired links return 410 immediately"),
            List.of("POST /api/v1/links (adds optional expiresAt)"),
            List.of("links.expires_at TIMESTAMPTZ NULL"),
            List.of("Add expires_at column and API field", "Enforce expiry on the redirect path", "Add regression test for expired redirects"),
            List.of("expired link returns 410", "past expiresAt rejected"),
            List.of("Clock skew between instances", "Expired links still counted if the check is skipped"),
            List.of("Resurrection of expired links through cache"),
            List.of("expiresAt", "isExpired")),

    ANALYTICS("Click analytics",
            List.of("analytics", "click", "stats", "track", "count visits", "kafka"),
            "Every successful redirect produces a click event that is aggregated into per-link statistics.",
            List.of("A redirect publishes exactly one event to the click topic", "Replayed events never double count", "Stats endpoint returns totals, 7 day series and top referrers"),
            List.of("GET /api/v1/links/{code}/stats"),
            List.of("click_events(event_id PK, code, clicked_at, referrer, visitor_hash)", "links.click_count"),
            List.of("Publish click event asynchronously", "Implement idempotent consumer with dead-letter topic", "Implement stats endpoint"),
            List.of("duplicate event does not double count", "poison message lands on the DLT", "publish failure never fails redirect"),
            List.of("Analytics is eventually consistent", "Broker outage drops events if publishing is best effort"),
            List.of("Personal data in click logs", "Event forgery through a public topic"),
            List.of("ClickPublisher", "ClickConsumer", "link.clicks")),

    RATE_LIMIT("Rate limiting",
            List.of("rate limit", "throttle", "abuse", "rate-limit"),
            "Link creation is rate limited per client to contain abuse.",
            List.of("The 61st create in a minute returns 429 with Retry-After"),
            List.of("POST /api/v1/links (429 response)"),
            List.of(),
            List.of("Add per-client fixed-window limiter in a servlet filter"),
            List.of("429 after limit", "window resets"),
            List.of("In-memory limiter is per instance and resets on restart", "Spoofable client identity behind proxies"),
            List.of("Mass link creation for spam", "X-Forwarded-For spoofing"),
            List.of("RateLimitFilter", "429")),

    AUTH("Authentication and ownership",
            List.of("auth", "login", "sign in", "api key", "account", "owner", "user accounts"),
            "Links belong to authenticated owners who alone can manage them.",
            List.of("Unauthenticated creates are rejected with 401", "Owners can list and disable only their own links"),
            List.of("POST /api/v1/auth/token", "GET /api/v1/links?owner=me"),
            List.of("users(id, email, password_hash)", "links.owner_id"),
            List.of("Choose an authentication scheme and session model", "Add ownership checks to every link endpoint"),
            List.of("401 without token", "403 on foreign link", "no IDOR on code lookup"),
            List.of("Broken access control (IDOR)", "Credential storage mistakes"),
            List.of("Credential stuffing", "Token theft", "IDOR across owners"),
            List.of("owner", "Authentication")),

    QR_CODE("QR codes",
            List.of("qr"),
            "Each short link can be rendered as a QR code image.",
            List.of("GET returns a PNG that decodes to the short URL"),
            List.of("GET /api/v1/links/{code}/qr"),
            List.of(),
            List.of("Add QR rendering endpoint"),
            List.of("QR decodes to the short URL"),
            List.of("Large image generation cost"),
            List.of("QR codes used for quishing"),
            List.of("qr")),

    BULK("Bulk creation",
            List.of("bulk", "batch import", "csv"),
            "Many links can be created in one request.",
            List.of("A batch of up to 100 URLs is processed with per-item results"),
            List.of("POST /api/v1/links/bulk"),
            List.of(),
            List.of("Add bulk endpoint with per-item validation"),
            List.of("partial failure reports per item"),
            List.of("Bulk endpoint amplifies abuse and rate limit bypass"),
            List.of("Rate limit evasion through batching"),
            List.of("bulk")),

    MALWARE_CHECK("Unsafe destination screening",
            List.of("malicious", "phishing", "malware", "safe browsing", "unsafe link"),
            "Targets are screened against an unsafe-destination list before a link is created.",
            List.of("A listed host is rejected with 400"),
            List.of("POST /api/v1/links (400 on blocked host)"),
            List.of("blocked_hosts(host PK, reason)"),
            List.of("Add blocklist lookup to link creation"),
            List.of("blocked host rejected"),
            List.of("False positives block legitimate links", "Blocklist freshness"),
            List.of("Phishing campaigns using the short domain"),
            List.of("blocked_hosts")),

    RELIABILITY("Reliability and resilience",
            List.of("reliab", "resilien", "retry", "retries", "dead letter", "high availab", "outage", "never break"),
            "Core flows keep working when dependencies degrade.",
            List.of("A broker outage never fails a redirect", "Failed events are retried then parked on a dead-letter topic"),
            List.of("GET /actuator/health"),
            List.of(),
            List.of("Decouple analytics from redirect with async publish", "Configure retries and dead-letter handling", "Expose health probes"),
            List.of("redirect succeeds with broker down", "health reports dependency state"),
            List.of("Silent event loss when the broker is down"),
            List.of("Resource exhaustion by retry storms"),
            List.of("DeadLetter", "startVirtualThread"));

    public final String label;
    public final List<String> keywords;
    public final String requirement;
    public final List<String> acceptance;
    public final List<String> endpoints;
    public final List<String> data;
    public final List<String> tasks;
    public final List<String> tests;
    public final List<String> risks;
    public final List<String> threats;
    /** Tokens that, when present in existing source, show the capability already exists. */
    public final List<String> codeSignals;

    Feature(String label, List<String> keywords, String requirement, List<String> acceptance, List<String> endpoints,
            List<String> data, List<String> tasks, List<String> tests, List<String> risks, List<String> threats,
            List<String> codeSignals) {
        this.label = label;
        this.keywords = keywords;
        this.requirement = requirement;
        this.acceptance = acceptance;
        this.endpoints = endpoints;
        this.data = data;
        this.tasks = tasks;
        this.tests = tests;
        this.risks = risks;
        this.threats = threats;
        this.codeSignals = codeSignals;
    }

    /** True when the feature changes persisted data. */
    public boolean touchesSchema() {
        return !data.isEmpty();
    }
}
