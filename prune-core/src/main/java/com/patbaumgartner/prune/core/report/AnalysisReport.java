package com.patbaumgartner.prune.core.report;

import java.util.List;
import java.util.Objects;

public record AnalysisReport(List<AnalysisIssue> issues, String summary, boolean conservativeMode,
		List<KeptSymbol> kept) {
	public AnalysisReport {
		issues = List.copyOf(issues);
		Objects.requireNonNull(summary, "summary");
		kept = List.copyOf(kept);
	}

	public AnalysisReport(List<AnalysisIssue> issues, String summary, boolean conservativeMode) {
		this(issues, summary, conservativeMode, List.of());
	}

	public int issueCount() {
		return issues.size();
	}

	public boolean hasAutoFixableIssues() {
		return issues.stream().anyMatch(AnalysisIssue::autoFixable);
	}
}
