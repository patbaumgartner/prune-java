package com.patbaumgartner.prune.core.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisReportTest {

    @Test
    void copiesIssuesSoLaterCallerMutationIsNotVisible() {
        var issues = new ArrayList<AnalysisIssue>();
        var report = new AnalysisReport(issues, "summary", true);

        issues.add(new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "A", "A.java", "unused", false));

        assertEquals(0, report.issueCount());
    }

    @Test
    void rejectsNullSummary() {
        assertThrows(NullPointerException.class, () -> new AnalysisReport(List.of(), null, true));
    }

    @Test
    void rejectsNullIssueFields() {
        assertThrows(NullPointerException.class,
                () -> new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "A", "A.java", null, false));
    }

}
