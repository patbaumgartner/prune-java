package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.report.AnalysisIssue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record FixPlan(List<FixAction> actions) {

	public FixPlan {
		actions = List.copyOf(actions);
	}

	public static FixPlan empty() {
		return new FixPlan(List.of());
	}

	public boolean isEmpty() {
		return actions.isEmpty();
	}

	public Set<AnalysisIssue> fixedIssues() {
		return actions.stream().map(FixAction::issue).collect(Collectors.toUnmodifiableSet());
	}
}
