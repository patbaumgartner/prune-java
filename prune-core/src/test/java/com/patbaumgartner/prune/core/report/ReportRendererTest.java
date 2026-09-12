package com.patbaumgartner.prune.core.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportRendererTest {

    @Test
    void rendersNoticeWhenGithubOutputHasNoIssues() {
        var renderer = new ReportRenderer();
        var report = new AnalysisReport(List.of(), "none", true);

        var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

        assertEquals("::notice::No unused code detected (conservative mode).", output);
    }

    @Test
    void escapesJsonContentInJsonOutput() {
        var renderer = new ReportRenderer();
        var issue = new AnalysisIssue(
                IssueType.UNUSED_METHOD,
                Severity.WARNING,
                "A\\\"B",
                "src/main/java/A.java",
                "message with \\ and \"",
                true
        );
        var report = new AnalysisReport(List.of(issue), "summary \"quoted\"", true);

        var output = renderer.render(report, OutputFormat.JSON);

        assertEquals(
                "{\"summary\":\"summary \\\"quoted\\\"\",\"conservativeMode\":true,\"issues\":[{\"type\":\"UNUSED_METHOD\",\"severity\":\"WARNING\",\"symbol\":\"A\\\\\\\"B\",\"location\":\"src/main/java/A.java\",\"message\":\"message with \\\\ and \\\"\",\"autoFixable\":true}]}",
                output
        );
    }

    @Test
    void escapesGithubWorkflowCommandSpecialCharacters() {
        var renderer = new ReportRenderer();
        var issue = new AnalysisIssue(
                IssueType.UNUSED_FIELD,
                Severity.WARNING,
                "field",
                "A.java",
                "line1%\nline2\rline3",
                false
        );
        var report = new AnalysisReport(List.of(issue), "n/a", true);

        var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

        assertEquals("::warning file=A.java::line1%25%0Aline2%0Dline3", output);
    }

    @Test
    void escapesPropertySeparatorsInGithubFileLocation() {
        var renderer = new ReportRenderer();
        var issue = new AnalysisIssue(
                IssueType.UNUSED_CLASS,
                Severity.WARNING,
                "Evil",
                "A.java,line=99,col=1,title=spoofed",
                "real message",
                false
        );
        var report = new AnalysisReport(List.of(issue), "n/a", true);

        var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

        assertEquals("::warning file=A.java%2Cline=99%2Ccol=1%2Ctitle=spoofed::real message", output);
    }

    @Test
    void escapesControlCharactersInTerminalOutput() {
        var renderer = new ReportRenderer();
        var issue = new AnalysisIssue(
                IssueType.UNUSED_METHOD,
                Severity.ERROR,
                "m",
                "A.java:1",
                "real finding\u001b[2K\rALL CLEAR",
                false
        );
        var report = new AnalysisReport(List.of(issue), "n/a", true);

        var output = renderer.render(report, OutputFormat.TERMINAL);

        assertEquals(
                "- [ERROR] A.java:1 :: real finding\\u001b[2K\\u000dALL CLEAR"
                        + System.lineSeparator() + "Summary: n/a",
                output
        );
    }
}
