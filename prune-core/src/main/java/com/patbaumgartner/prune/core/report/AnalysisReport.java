package com.patbaumgartner.prune.core.report;

import java.util.List;
import java.util.Objects;

public record AnalysisReport(
        List<AnalysisIssue> issues,
        String summary,
        boolean conservativeMode
) {
    public AnalysisReport {
        issues = List.copyOf(issues);
        Objects.requireNonNull(summary, "summary");
    }

    public int issueCount() {
        return issues.size();
    }

    public boolean hasAutoFixableIssues() {
        return issues.stream().anyMatch(AnalysisIssue::autoFixable);
    }
}
