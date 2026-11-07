package com.agentic.orchestrator.model;

import com.agentic.orchestrator.model.Model.Question;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Interprets intent: features, vague wording, missing facts, forbidden asks. Pure and unit tested. */
@Component
public class RequirementAnalyzer {

    public static final String CLARIFICATIONS = "Clarifications:";
    private static final Pattern ANSWER_LINE = Pattern.compile("^\\[([a-z_]+)]\\s*(.+)$");
    private static final Pattern BROWNFIELD = Pattern.compile(
            "\\b(existing|already|currently|current (api|code|service)|refactor|bug|fix|backward compatible|our (links|service|api))\\b");

    private record Vague(String slot, List<String> triggers, String question, String assumption) {}

    private static final List<Vague> VAGUE = List.of(
            new Vague("smart_meaning", List.of("smart", "smarter", "intelligent"),
                    "What should \"smarter\" links actually do?",
                    "Use click analytics to auto-expire stale links and screen malicious destinations"),
            new Vague("latency_target", List.of("fast", "faster", "quick", "performance", "speed"),
                    "What redirect latency (p95) and peak throughput must we meet?",
                    "p95 redirect under 50 ms at 500 requests per second"),
            new Vague("security_scope", List.of("secure", "security", "safe"),
                    "Which threats matter most: abuse, phishing, data exposure, account takeover?",
                    "Block private targets, rate limit creation, hash visitor identifiers"),
            new Vague("scale_target", List.of("scalable", "scale"),
                    "How much growth must the design absorb?",
                    "10x current traffic using stateless instances"),
            new Vague("ux_goal", List.of("better", "improve", "modern", "user-friendly", "intuitive", "easy"),
                    "Which user outcome should improve and how will we measure it?",
                    "Fewer steps to create a link, measured by time to first link"));

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(disable|remove|bypass|turn off) (the )?(auth|authentication|security|validation|rate limit|audit)"),
            Pattern.compile("skip (the )?(review|approval|tests?|validation|security)"),
            Pattern.compile("without (human )?(approval|review)"),
            Pattern.compile("deploy (it )?(directly )?to production (automatically|without)"),
            Pattern.compile("store passwords? in plain"));

    public RequirementModel analyze(String rawText, String forcedMode) {
        String text = rawText == null ? "" : rawText.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        int split = text.indexOf(CLARIFICATIONS);
        String base = split >= 0 ? text.substring(0, split) : text;
        String baseLower = base.toLowerCase(Locale.ROOT);

        Map<String, String> answers = new LinkedHashMap<>();
        if (split >= 0) {
            for (String line : text.substring(split + CLARIFICATIONS.length()).split("\\R")) {
                var m = ANSWER_LINE.matcher(line.strip());
                if (m.matches()) answers.put(m.group(1), m.group(2).strip());
            }
        }

        var features = EnumSet.noneOf(Feature.class);
        for (Feature f : Feature.values()) {
            for (String k : f.keywords) {
                if (lower.contains(k)) { features.add(f); break; }
            }
        }
        if (features.contains(Feature.SHORTEN)) features.add(Feature.REDIRECT);
        if (features.contains(Feature.ANALYTICS)) features.add(Feature.REDIRECT);

        boolean brownfield = forcedMode != null ? forcedMode.equalsIgnoreCase("BROWNFIELD")
                : BROWNFIELD.matcher(baseLower).find();

        var vagueTerms = new ArrayList<String>();
        var open = new ArrayList<Question>();
        var assumptions = new ArrayList<String>();
        for (Vague v : VAGUE) {
            String hit = v.triggers().stream().filter(t -> containsWord(baseLower, t)).findFirst().orElse(null);
            if (hit == null) continue;
            vagueTerms.add(hit);
            if (answers.containsKey(v.slot())) {
                assumptions.add(v.slot() + ": " + answers.get(v.slot()) + " (confirmed by a human)");
            } else {
                open.add(new Question(v.slot(), v.question(), v.assumption()));
            }
        }
        boolean analytics = features.contains(Feature.ANALYTICS);
        if (analytics && !lower.matches("(?s).*(retain|retention|retaining|days).*") && !answers.containsKey("retention")) {
            open.add(new Question("retention", "How long may click data be kept?", "90 days, no raw IP addresses"));
        }
        if (!open.isEmpty() && open.size() < 2) {
            for (Question q : open) assumptions.add(q.slot() + ": " + q.assumption() + " (assumed, not confirmed)");
        }

        boolean personal = analytics || lower.matches("(?s).*(ip address|pii|personal data|email).*");
        var forbidden = new ArrayList<String>();
        for (Pattern p : FORBIDDEN) {
            var m = p.matcher(baseLower);
            if (m.find()) forbidden.add(m.group());
        }
        return new RequirementModel(text, titleOf(base), brownfield, features, List.copyOf(vagueTerms),
                List.copyOf(open), Map.copyOf(answers), personal, List.copyOf(forbidden), List.copyOf(assumptions));
    }

    static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    static String titleOf(String base) {
        String first = base.strip().split("(?<=[.!?])\\s", 2)[0].replaceAll("\\s+", " ");
        return first.length() > 80 ? first.substring(0, 77) + "..." : first;
    }
}
