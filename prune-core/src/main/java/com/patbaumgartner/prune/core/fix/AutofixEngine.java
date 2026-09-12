package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;

import java.util.List;
import java.util.Set;

public interface AutofixEngine {

	FixPlan plan(AnalysisReport report);

	FixResult apply(FixPlan plan);

	// Plans and applies in one step; the returned report lists only what still needs a
	// human.
	default AnalysisReport fix(AnalysisReport report) {
		FixPlan plan = plan(report);
		FixResult result = apply(plan);
		Set<AnalysisIssue> fixed = plan.fixedIssues();
		List<AnalysisIssue> remaining = report.issues().stream().filter(issue -> !fixed.contains(issue)).toList();
		String summary = "Applied " + result.actionsApplied() + " autofix(es) in " + result.filesChanged()
				+ " file(s); " + remaining.size() + " issue(s) remain (conservative mode).";
		return new AnalysisReport(remaining, summary, report.conservativeMode(), report.kept());
	}

}
