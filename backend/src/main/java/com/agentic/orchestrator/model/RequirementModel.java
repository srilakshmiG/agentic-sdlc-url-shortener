package com.agentic.orchestrator.model;

import com.agentic.orchestrator.model.Model.Question;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured reading of a free-text requirement. Recomputed deterministically from the text. */
public record RequirementModel(String text, String title, boolean brownfield, Set<Feature> features,
                               List<String> vagueTerms, List<Question> openQuestions,
                               Map<String, String> answers, boolean collectsPersonalData,
                               List<String> forbiddenAsks, List<String> assumptions) {

    /** Two or more unresolved questions pause the run for a human; one is recorded as an assumption. */
    public boolean needsClarification() {
        return openQuestions.size() >= 2;
    }

    public boolean has(Feature f) {
        return features.contains(f);
    }
}
