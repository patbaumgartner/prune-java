package com.patbaumgartner.prune.cli;

import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneCliApplicationTest {

    @Test
    void checkCommandReturnsSuccessWithNoIssues() {
        var app = new PruneCliApplication();
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"check", "--format=terminal"},
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().contains("No unused code detected"));
        assertEquals("", stderr.toString());
    }

    @Test
    void fixCommandReturnsSuccessForScaffold() {
        var app = new PruneCliApplication();
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"fix", "--format=terminal"},
                new PrintStream(stdout),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().contains("No unused code detected"));
    }

    @Test
    void invalidFormatReturnsError() {
        var app = new PruneCliApplication();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"check", "--format=invalid"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr)
        );

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unsupported format: invalid"));
    }

    @Test
    void checkCommandReturnsOneWhenIssuesAreReported() {
        var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "A", "A.java", "unused", false);
        UnusedCodeAnalyzer analyzer = config -> new AnalysisReport(List.of(issue), "1 issue", true);
        var app = new PruneCliApplication(analyzer, new ReportRenderer());

        int exitCode = app.run(
                new String[]{"check", "--format=terminal"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(1, exitCode);
    }

    @Test
    void unknownCommandReturnsUsageError() {
        var app = new PruneCliApplication();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"purge"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr)
        );

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unknown command: purge"));
    }

    @Test
    void unknownOptionReturnsUsageError() {
        var app = new PruneCliApplication();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"check", "--formta=json"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr)
        );

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unknown option: --formta=json"));
    }

    @Test
    void ciFlagSelectsGithubAnnotationOutput() {
        var app = new PruneCliApplication();
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(
                new String[]{"check", "--ci"},
                new PrintStream(stdout),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().startsWith("::notice::"));
    }

    @Test
    void commandParsingIgnoresTheDefaultLocale() {
        var original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var app = new PruneCliApplication();
            var stderr = new ByteArrayOutputStream();

            int exitCode = app.run(
                    new String[]{"FIX"},
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(stderr)
            );

            assertEquals(0, exitCode);
            assertEquals("", stderr.toString());
        } finally {
            Locale.setDefault(original);
        }
    }
}
