package com.agentic.orchestrator.agents;

import static com.agentic.orchestrator.agents.Agent.md;

import com.agentic.orchestrator.model.Feature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Brownfield reasoning over the real source tree: finds which files, endpoints, tables and topics are
 * touched by the requested capabilities and whether each capability already exists.
 */
@Component
class CodebaseAgent implements Agent {

    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern BASE = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)");
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPIC = Pattern.compile("topics\\s*=\\s*\"([^\"]+)\"");

    private final Path root;

    CodebaseAgent(@Value("${app.orchestrator.codebase-path}") String path) { this.root = Path.of(path); }

    @Override public String id() { return "codebase-agent"; }

    @Override public Set<Capability> capabilities() { return Set.of(Capability.READ_REPO, Capability.WRITE_ARTIFACT); }

    @Override
    public AgentResult execute(AgentContext ctx) throws IOException {
        var requested = ctx.model().features();
        var sb = new StringBuilder("# Codebase impact analysis\n\n");
        if (!Files.isDirectory(root)) {
            sb.append("> Source tree not available at `").append(root).append("`. Analysis is degraded.\n\n## Impacted files\n- unknown\n");
            return AgentResult.of("Source tree unavailable", md("impact-analysis.md", sb.toString()));
        }
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(x -> !x.toString().replace('\\', '/').contains("/orchestrator/")).filter(x -> {
                String n = x.toString();
                return n.endsWith(".java") || n.endsWith(".sql");
            }).limit(500).toList()) {
                if (Files.size(p) < 200_000) sources.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        Map<String, Set<String>> byFeature = new LinkedHashMap<>();
        Map<String, Integer> score = new LinkedHashMap<>();
        for (Feature f : requested) {
            var files = new TreeSet<String>();
            for (var e : sources.entrySet())
                for (String sig : f.codeSignals)
                    if (e.getValue().contains(sig)) { files.add(e.getKey()); score.merge(e.getKey(), 1, Integer::sum); break; }
            byFeature.put(f.name(), files);
        }
        var endpoints = new ArrayList<String>();
        var tables = new LinkedHashSet<String>();
        var topics = new LinkedHashSet<String>();
        for (var e : sources.entrySet()) {
            String src = e.getValue();
            String base = "";
            var bm = BASE.matcher(src);
            if (bm.find()) base = bm.group(1);
            var mm = MAPPING.matcher(src);
            while (mm.find()) endpoints.add(mm.group(1).toUpperCase() + " " + base + (mm.group(2) == null ? "" : mm.group(2)) + "  (" + simple(e.getKey()) + ")");
            var tm = TABLE.matcher(src);
            while (tm.find()) tables.add(tm.group(1));
            var km = TOPIC.matcher(src);
            while (km.find()) topics.add(km.group(1));
        }

        sb.append("Scanned ").append(sources.size()).append(" source files.\n\n## Capability status\n");
        sb.append("| Capability | Status | Evidence |\n|---|---|---|\n");
        int present = 0;
        for (Feature f : requested) {
            var files = byFeature.get(f.name());
            boolean there = !files.isEmpty();
            if (there) present++;
            sb.append("| ").append(f.label).append(" | ").append(there ? "already present - extend or verify" : "absent - build")
                    .append(" | ").append(there ? files.size() + " files" : "none").append(" |\n");
        }
        sb.append("\n## Impacted files\n");
        score.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append("- `").append(e.getKey()).append("` touches ").append(e.getValue()).append(" requested capabilities\n"));
        if (score.isEmpty()) sb.append("- none matched; this looks like new surface area\n");
        sb.append("\n## Impacted modules\n");
        var modules = new TreeSet<String>();
        score.keySet().forEach(f -> modules.add(module(f)));
        sb.append(Md.bullets(modules));
        sb.append("\n## Existing API surface\n").append(Md.bullets(endpoints));
        sb.append("\n## Existing tables\n").append(Md.bullets(tables));
        sb.append("\n## Existing topics\n").append(Md.bullets(topics));
        sb.append("\n## Data flows to protect\n");
        sb.append("- Create: controller -> service -> repository -> `links`\n");
        sb.append("- Redirect: controller -> repository -> async publish to Kafka (must not block)\n");
        sb.append("- Analytics: topic -> idempotent consumer -> `click_events` + `links.click_count`\n");
        sb.append("\n## Compatibility\n- Changes must be additive: new request fields optional, new columns nullable\n");
        sb.append("- Existing clients of POST /api/v1/links and GET /{code} must keep working\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of(present + " of " + requested.size() + " capabilities already exist; "
                        + score.size() + " files impacted", md("impact-analysis.md", sb.toString()))
                .withDecisions(new DecisionDraft("Extend existing code instead of rewriting",
                        present + " requested capabilities already have evidence in the source tree; "
                                + "regression tests are cheaper and safer than new implementations"));
    }

    private static String simple(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private static String module(String file) {
        String[] parts = file.split("/");
        for (int i = 0; i < parts.length - 1; i++) if (parts[i].equals("agentic") && i + 1 < parts.length - 1) return parts[i + 1];
        return parts.length > 1 ? parts[0] : "root";
    }
}

/** STRIDE-flavoured threat model derived from the requested capabilities. */
@Component
class ThreatModelAgent implements Agent {

    @Override public String id() { return "threat-model-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        var m = ctx.model();
        var sb = new StringBuilder("# Threat model\n\n## Assets\n- Short-link mapping integrity\n- Visitor privacy\n- Availability of the redirect path\n\n## Threats\n");
        sb.append("| Capability | Threat | Mitigation |\n|---|---|---|\n");
        for (Feature f : m.features()) {
            for (int i = 0; i < f.threats.size(); i++)
                sb.append("| ").append(f.label).append(" | ").append(f.threats.get(i)).append(" | ").append(mitigation(f, i)).append(" |\n");
        }
        sb.append("| All | Tampering with this orchestration record | Hash-chained audit log, verified on demand |\n");
        sb.append("\n## Residual risk\n");
        sb.append("- Reputation abuse of the short domain cannot be eliminated, only reduced and monitored\n");
        if (m.collectsPersonalData()) sb.append("- Visitor hashes are pseudonymous, not anonymous; retention limits apply\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of(m.features().stream().mapToInt(f -> f.threats.size()).sum() + " threats with mitigations",
                md("threat-model.md", sb.toString()));
    }

    private static String mitigation(Feature f, int i) {
        return switch (f) {
            case SHORTEN -> i == 0 ? "Scheme allowlist, length cap, optional blocklist, abuse reporting"
                    : "Reject private, loopback and link-local hosts before storing";
            case REDIRECT -> "Location header built from a validated URI only";
            case CUSTOM_ALIAS -> "Reserved-word list and charset allowlist";
            case EXPIRY -> "Expiry checked on every redirect; no cache without TTL below expiry";
            case ANALYTICS -> i == 0 ? "Hash visitors, never store raw IP, bound retention"
                    : "Broker not exposed publicly; producer authenticated in production";
            case RATE_LIMIT -> "Per-client window; trust X-Forwarded-For only behind a known proxy";
            case AUTH -> "Short-lived tokens, hashed secrets, ownership check on every lookup";
            case QR_CODE -> "Size cap and rate limit on rendering";
            case BULK -> "Per-item validation and counting against the same rate limit";
            case MALWARE_CHECK -> "Blocklist with appeal path; fail open for availability, log for review";
            case RELIABILITY -> "Bounded retries with backoff and a dead-letter topic";
        };
    }
}
