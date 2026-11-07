package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.model.Model.Artifact;
import com.agentic.orchestrator.model.Model.NodeSpec;
import com.agentic.orchestrator.model.Model.Question;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An agent does one bounded job and returns artifacts. It cannot deploy, migrate or call out: the
 * only capabilities it may use are declared here and enforced by the policy engine after the call.
 * Implementations in this prototype are deterministic; an LLM-backed agent would implement the
 * same interface and be held to the same guardrails.
 */
public interface Agent {

    enum Capability { READ_REPO, WRITE_ARTIFACT }

    String id();

    default Set<Capability> capabilities() { return Set.of(Capability.WRITE_ARTIFACT); }

    AgentResult execute(AgentContext ctx) throws Exception;

    /** Compensation hook for external side effects. Artifacts are revoked by the engine itself. */
    default void rollback(AgentContext ctx) {}

    record AgentContext(UUID runId, NodeSpec spec, RequirementModel model, int requirementVersion,
                        Map<String, Artifact> inputs, String feedback, int attempt) {

        public String input(String name) {
            Artifact a = inputs.get(name);
            return a == null ? "" : a.content();
        }

        public String feedbackNote() {
            return feedback == null || feedback.isBlank() ? ""
                    : "\n## Reviewer feedback addressed\n" + feedback.strip() + "\n";
        }
    }

    record ArtifactDraft(String name, String contentType, String content) {}

    record DecisionDraft(String decision, String rationale) {}

    record AgentResult(List<ArtifactDraft> artifacts, List<DecisionDraft> decisions, List<Question> questions,
                       List<String> actions, String summary) {

        public static AgentResult of(String summary, ArtifactDraft... artifacts) {
            return new AgentResult(List.of(artifacts), List.of(), List.of(), List.of("WRITE_ARTIFACT"), summary);
        }

        public AgentResult withDecisions(DecisionDraft... d) {
            return new AgentResult(artifacts, List.of(d), questions, actions, summary);
        }

        public AgentResult withQuestions(List<Question> q) {
            return new AgentResult(artifacts, decisions, q, actions, summary);
        }

        public AgentResult withExtraAction(String action) {
            var a = new java.util.ArrayList<>(actions);
            a.add(action);
            return new AgentResult(artifacts, decisions, questions, List.copyOf(a), summary);
        }
    }

    static ArtifactDraft md(String name, String content) {
        return new ArtifactDraft(name, "text/markdown", content);
    }
}
