package com.patbaumgartner.prune.core.report;

import java.util.Objects;

public record AnalysisIssue(IssueType type, Severity severity, String symbol, String location, String message,
		boolean autoFixable) {
	public AnalysisIssue {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(symbol, "symbol");
		Objects.requireNonNull(location, "location");
		Objects.requireNonNull(message, "message");
	}
}
