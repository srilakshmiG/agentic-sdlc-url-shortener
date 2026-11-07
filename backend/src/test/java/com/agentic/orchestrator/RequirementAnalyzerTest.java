package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.orchestrator.api.ScenarioCatalog;
import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.RequirementAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequirementAnalyzerTest {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();
    private final ScenarioCatalog scenarios = new ScenarioCatalog();

    private String req(String id) {
        return scenarios.all().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().requirement();
    }

    @Test
    void greenfieldScenarioIsClearEnoughToProceed() {
        var m = analyzer.analyze(req("GREENFIELD"), "GREENFIELD");
        assertFalse(m.brownfield());
        assertTrue(m.features().containsAll(List.of(Feature.SHORTEN, Feature.REDIRECT, Feature.CUSTOM_ALIAS,
                Feature.EXPIRY, Feature.ANALYTICS, Feature.RATE_LIMIT, Feature.RELIABILITY)));
        assertFalse(m.needsClarification());
        assertTrue(m.collectsPersonalData());
    }

    @Test
    void brownfieldIsDetectedFromWording() {
        var m = analyzer.analyze(req("BROWNFIELD"), null);
        assertTrue(m.brownfield());
        assertTrue(m.has(Feature.EXPIRY));
        assertTrue(m.has(Feature.CUSTOM_ALIAS));
        assertFalse(m.needsClarification());
    }

    @Test
    void vagueRequestNeedsClarification() {
        var m = analyzer.analyze(req("AMBIGUOUS"), null);
        assertEquals(3, m.openQuestions().size());
        assertTrue(m.needsClarification());
        assertTrue(m.vagueTerms().containsAll(List.of("smarter", "faster", "secure")));
    }

    @Test
    void answersResolveQuestionsAndExpandFeatures() {
        String text = req("AMBIGUOUS") + "\n\nClarifications:\n[smart_meaning] auto-expire stale links with click analytics\n"
                + "[latency_target] p95 under 50 ms\n[security_scope] rate limit creation";
        var m = analyzer.analyze(text, null);
        // the three vague terms are resolved; the new analytics capability raises one smaller question, below the pause threshold
        assertEquals(List.of("retention"), m.openQuestions().stream().map(q -> q.slot()).toList());
        assertFalse(m.needsClarification());
        assertEquals(3, m.answers().size());
        assertTrue(m.has(Feature.EXPIRY));
        assertTrue(m.has(Feature.ANALYTICS));
        assertTrue(m.has(Feature.RATE_LIMIT));
    }

    @Test
    void requirementsThatWeakenControlsAreFlagged() {
        var m = analyzer.analyze("Build a URL shortener but disable authentication and skip the review.", null);
        assertEquals(2, m.forbiddenAsks().size());
    }

    @Test
    void emptyRequirementYieldsNoFeaturesWithoutFailing() {
        assertTrue(analyzer.analyze("", null).features().isEmpty());
    }
}
