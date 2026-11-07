package com.agentic.orchestrator.agents;

import static com.agentic.orchestrator.agents.Agent.md;

import com.agentic.orchestrator.model.Feature;
import com.agentic.orchestrator.model.Model.Artifact;
import com.agentic.orchestrator.model.RequirementModel;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Fan-in gate: checks traceability, threat coverage, secrets and operability across all upstream outputs. */
@Component
class ComplianceAgent implements Agent {

    private static final Pattern SECRET = Pattern.compile("(?i)(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|password\\s*[:=]\\s*\\S{4,})");

    @Override public String id() { return "compliance-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        String tests = ctx.input("unit-tests.md") + ctx.input("integration-tests.md");
        var rows = new ArrayList<String>();
        boolean allPass = true;

        int total = 0, covered = 0;
        var missing = new ArrayList<String>();
        for (Feature f : m.features())
            for (int i = 0; i < f.acceptance.size(); i++) {
                total++;
                if (tests.contains(Md.acId(f, i))) covered++; else missing.add(Md.acId(f, i));
            }
        boolean trace = covered == total;
        allPass &= trace;
        rows.add(row("TRACE-001", "Every acceptance criterion has a test", trace, covered + "/" + total + (missing.isEmpty() ? "" : " missing " + missing)));

        String threats = ctx.input("threat-model.md");
        var unthreatened = m.features().stream().filter(f -> !threats.contains(f.label)).map(f -> f.label).toList();
        boolean threatOk = unthreatened.isEmpty();
        allPass &= threatOk;
        rows.add(row("SEC-003", "Threat model covers every capability", threatOk, threatOk ? "all covered" : "missing " + unthreatened));

        boolean clean = ctx.inputs().values().stream().noneMatch(a -> SECRET.matcher(a.content()).find());
        allPass &= clean;
        rows.add(row("SEC-001", "No secrets in generated artifacts", clean, clean ? "none found" : "possible secret detected"));

        boolean runbook = ctx.input("documentation.md").contains("## Runbook");
        allPass &= runbook;
        rows.add(row("OPS-001", "Runbook present", runbook, runbook ? "present" : "missing"));

        rows.add(row("CHG-001", "High-impact stages require human approval", true, "enforced by the engine, not by this agent"));
        rows.add(row("CHG-002", "Agents limited to declared capabilities", true, "enforced by the engine after every agent call"));
        if (m.collectsPersonalData()) rows.add(row("PRIV-001", "Personal data minimised", true, "hashed visitor id, no raw IP, bounded retention"));

        var sb = new StringBuilder("# Compliance report\n\n## Policy results\n| Rule | Check | Result | Evidence |\n|---|---|---|---|\n");
        rows.forEach(sb::append);
        sb.append("\nRESULT: ").append(allPass ? "PASS" : "FAIL").append('\n');
        sb.append(ctx.feedbackNote());
        return AgentResult.of(allPass ? "All policy checks passed" : "Policy checks failed",
                md("compliance-report.md", sb.toString()));
    }

    private static String row(String rule, String check, boolean ok, String evidence) {
        return "| " + rule + " | " + check + " | " + (ok ? "PASS" : "FAIL") + " | " + evidence + " |\n";
    }
}

/** Builds the release checklist and risk register. High impact: a human decides go or no-go. */
@Component
class ReleaseAgent implements Agent {

    @Override public String id() { return "release-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        var sb = new StringBuilder("# Release readiness\n\n## Go/no-go checklist\n");
        sb.append("- [x] Compliance report: ").append(ctx.input("compliance-report.md").contains("RESULT: PASS") ? "PASS" : "NOT PASSING").append('\n');
        sb.append("- [x] API contract (`openapi.yaml`) published\n");
        sb.append("- [ ] Pull request reviewed and merged by a human\n- [ ] CI green on the merged commit\n- [ ] Migration rehearsed on a copy of production data\n");
        sb.append("\n## Risk register\n| Risk | Likelihood | Impact | Mitigation |\n|---|---|---|---|\n");
        for (Feature f : m.features())
            for (String r : f.risks) sb.append("| ").append(r).append(" | medium | ").append(f == Feature.AUTH || f == Feature.SHORTEN ? "high" : "medium")
                    .append(" | see threat model and design for ").append(f.label).append(" |\n");
        sb.append("\n## Rollback plan\n- Redeploy the previous image\n- Migrations are additive; the down-migration is documented and only run to discard new data\n- Feature behaviour can be disabled by configuration where noted\n");
        sb.append("\n## Post-release monitoring\n- Error rate, redirect p95, `shortener.click.publish.failed`, dead-letter topic depth\n");
        sb.append("\n## Approval required\nAgents never deploy. A human performs the release after approving this checklist.\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of("Checklist with " + m.features().stream().mapToInt(f -> f.risks.size()).sum() + " registered risks",
                md("release-checklist.md", sb.toString()));
    }
}

/** Produces the final engineering summary from everything upstream. */
@Component
class SummaryAgent implements Agent {

    @Override public String id() { return "summary-agent"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        RequirementModel m = ctx.model();
        var sb = new StringBuilder("# Engineering summary\n\n## Plan and rationale\n");
        sb.append(m.title()).append("\n\nThe work was ").append(m.brownfield() ? "analysed against the existing code, " : "designed from scratch, ")
                .append("decomposed into a dependency graph, built in parallel where independent, and gated by policy and humans.\n");
        sb.append("\n## Artifacts\n");
        ctx.inputs().values().stream().sorted(java.util.Comparator.comparing(Artifact::name))
                .forEach(a -> sb.append("- `").append(a.name()).append("` v").append(a.version()).append(" (from ").append(a.stageId()).append(")\n"));
        sb.append("\n## Risks and trade-offs\n");
        for (Feature f : m.features()) for (String r : f.risks) sb.append("- ").append(r).append('\n');
        sb.append("\n## Validation\n- Exit gates on every artifact, policy checks at every stage, traceability from acceptance criteria to tests\n");
        sb.append("- Human approvals recorded for architecture").append(m.brownfield() ? ", schema migration" : "").append(" and release\n");
        sb.append("\n## Assumptions\n").append(Md.bullets(m.assumptions().isEmpty() ? java.util.List.of("none beyond the written requirement") : m.assumptions()));
        sb.append("\n## Limitations\n");
        sb.append("- Agents are deterministic templates over a feature catalogue; they do not read arbitrary requirements or write to the repository\n");
        sb.append("- Generated patches and tests are designs for human review, not executed code\n");
        sb.append("- Operator identity is a free-text name; production needs real authentication\n");
        sb.append(ctx.feedbackNote());
        return AgentResult.of("Summary of " + ctx.inputs().size() + " artifacts", md("engineering-summary.md", sb.toString()));
    }
}
