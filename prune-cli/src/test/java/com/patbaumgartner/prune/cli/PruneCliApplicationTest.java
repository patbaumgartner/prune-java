package com.patbaumgartner.prune.cli;

import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.FixAction;
import com.patbaumgartner.prune.core.fix.FixPlan;
import com.patbaumgartner.prune.core.fix.FixResult;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneCliApplicationTest {

    private static final AnalysisIssue FIXABLE = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING,
            "a.B#m", "src/main/java/a/B.java:3:5", "Private method m is never used", true);
    private static final AnalysisIssue MANUAL = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING,
            "a.C", "src/main/java/a/C.java:1:7", "Class C is never referenced", false);

    @TempDir
    Path tempDir;

    @Test
    void checkCommandReturnsSuccessWithNoIssues() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--format=terminal"}, new PrintStream(stdout), new PrintStream(stderr));

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().contains("No unused code detected"));
        assertEquals("", stderr.toString());
    }

    @Test
    void checkCommandReturnsOneAndRendersIssuesWhenIssuesAreReported() {
        var app = new PruneCliApplication(config -> new AnalysisReport(List.of(MANUAL), "1 issue", true), new ReportRenderer());
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--format=terminal"}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, exitCode);
        assertEquals("- [WARNING] src/main/java/a/C.java:1:7 :: Class C is never referenced" + System.lineSeparator()
                + "Summary: 1 issue" + System.lineSeparator(), stdout.toString());
    }

    @Test
    void checkCommandAnalyzesTheWorkingDirectoryByDefaultAndTheRootOptionOtherwise() throws IOException {
        var roots = new ArrayList<Path>();
        var dryRuns = new ArrayList<Boolean>();
        UnusedCodeAnalyzer analyzer = config -> {
            roots.add(config.projectRoot());
            dryRuns.add(config.dryRun());
            return emptyReport();
        };
        var app = new PruneCliApplication(analyzer, new ReportRenderer());
        var other = Files.createDirectory(tempDir.resolve("other"));

        app.run(new String[]{"check"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        app.run(new String[]{"check", "--root=" + other}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(List.of(Path.of("."), other), roots);
        assertEquals(List.of(true, true), dryRuns);
    }

    @Test
    void rootOptionMustNameAnExistingDirectory() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--root=" + tempDir.resolve("missing")},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().startsWith("Project root is not a directory: "));
    }

    @Test
    void excludeOptionIsRepeatableAndReachesTheAnalyzerAsExcludePatterns() {
        var excludes = new ArrayList<List<String>>();
        UnusedCodeAnalyzer analyzer = config -> {
            excludes.add(config.excludePatterns());
            return emptyReport();
        };
        var app = new PruneCliApplication(analyzer, new ReportRenderer());

        app.run(new String[]{"check", "--exclude=**/generated/**", "--exclude=**/*Stub.java"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        app.run(new String[]{"check"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(List.of(List.of("**/generated/**", "**/*Stub.java"), List.of()), excludes);
    }

    @Test
    void excludeOptionRejectsAnEmptyPattern() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--exclude="}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertEquals("Option --exclude needs a glob pattern" + System.lineSeparator(), stderr.toString());
    }

    @Test
    void excludeOptionRejectsAnUnclosedBraceGroupAsAUsageError() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--exclude=**/{Foo"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertEquals("Invalid glob pattern '**/{Foo': missing '}'" + System.lineSeparator(), stderr.toString());
    }

    @Test
    void fixCommandAppliesThePlanAndReportsOnlyWhatRemains() {
        var engine = new RecordingEngine();
        var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE, MANUAL), "2 issues", true),
                new ReportRenderer(), root -> engine);
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"fix", "--root=" + tempDir, "--format=json"},
                new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, exitCode);
        assertEquals(List.of(FIXABLE), engine.plannedFor.get(0).issues());
        assertTrue(engine.applied);
        assertEquals("{\"summary\":\"Applied 1 autofix(es) in 1 file(s); 1 issue(s) remain (conservative mode).\","
                + "\"conservativeMode\":true,\"issues\":[{\"type\":\"UNUSED_CLASS\",\"severity\":\"WARNING\",\"symbol\":\"a.C\","
                + "\"location\":\"src/main/java/a/C.java:1:7\",\"message\":\"Class C is never referenced\",\"autoFixable\":false}]}"
                + System.lineSeparator(), stdout.toString());
    }

    @Test
    void fixCommandReturnsZeroWhenNothingRemains() {
        var engine = new RecordingEngine();
        var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE), "1 issue", true),
                new ReportRenderer(), root -> engine);
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"fix", "--root=" + tempDir}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().contains("No unused code detected"));
    }

    @Test
    void fixCommandPassesTheAnalyzedRootToTheEngineAndDisablesDryRun() {
        var roots = new ArrayList<Path>();
        var dryRuns = new ArrayList<Boolean>();
        UnusedCodeAnalyzer analyzer = config -> {
            dryRuns.add(config.dryRun());
            return emptyReport();
        };
        var app = new PruneCliApplication(analyzer, new ReportRenderer(), root -> {
            roots.add(root);
            return new RecordingEngine();
        });

        app.run(new String[]{"fix", "--root=" + tempDir}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(List.of(tempDir), roots);
        assertEquals(List.of(false), dryRuns);
    }

    @Test
    void analysisFailuresExitWithThreeAndExplainOnStderr() {
        UnusedCodeAnalyzer analyzer = config -> {
            throw new UncheckedIOException(new IOException("disk on fire"));
        };
        var app = new PruneCliApplication(analyzer, new ReportRenderer());
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check"}, new PrintStream(stdout), new PrintStream(stderr));

        assertEquals(3, exitCode);
        assertEquals("", stdout.toString());
        assertTrue(stderr.toString().contains("prune-java failed: java.io.IOException: disk on fire"));
    }

    @Test
    void staleFixPlansExitWithThreeWithoutRenderingAReport() {
        var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE), "1", true), new ReportRenderer(),
                root -> new RecordingEngine() {
                    @Override
                    public FixResult apply(FixPlan plan) {
                        throw new IllegalStateException("B.java changed since the fix was planned");
                    }
                });
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"fix", "--root=" + tempDir}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(3, exitCode);
        assertTrue(stderr.toString().contains("changed since the fix was planned"));
    }

    @Test
    void invalidFormatReturnsError() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--format=invalid"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unsupported format: invalid"));
    }

    @Test
    void unknownCommandReturnsUsageError() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"purge"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unknown command: purge"));
    }

    @Test
    void unknownOptionReturnsUsageError() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stderr = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--formta=json"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

        assertEquals(2, exitCode);
        assertTrue(stderr.toString().contains("Unknown option: --formta=json"));
    }

    @Test
    void helpPrintsUsageIncludingTheRootOption() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"--help"}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertEquals("Usage: prune-java <check|fix> [--root=<dir>] [--exclude=<glob>]... [--ci] [--format=terminal|github|json]"
                + System.lineSeparator(), stdout.toString());
    }

    @Test
    void ciFlagSelectsGithubAnnotationOutputAndCiMode() {
        var ciModes = new ArrayList<Boolean>();
        UnusedCodeAnalyzer analyzer = config -> {
            ciModes.add(config.ciMode());
            return emptyReport();
        };
        var app = new PruneCliApplication(analyzer, new ReportRenderer());
        var stdout = new ByteArrayOutputStream();

        int exitCode = app.run(new String[]{"check", "--ci"}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().startsWith("::notice::"));
        assertEquals(List.of(true), ciModes);
    }

    @Test
    void explicitFormatWinsOverCiDefault() {
        var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
        var stdout = new ByteArrayOutputStream();

        app.run(new String[]{"check", "--ci", "--format=json"}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertTrue(stdout.toString().startsWith("{\"summary\":"));
    }

    @Test
    void commandParsingIgnoresTheDefaultLocale() {
        var original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer(), root -> new RecordingEngine());
            var stderr = new ByteArrayOutputStream();

            int exitCode = app.run(new String[]{"FIX", "--root=" + tempDir}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));

            assertEquals(0, exitCode);
            assertEquals("", stderr.toString());
        } finally {
            Locale.setDefault(original);
        }
    }

    private static AnalysisReport emptyReport() {
        return new AnalysisReport(List.of(), "empty", true);
    }

    private static class RecordingEngine implements AutofixEngine {
        final List<AnalysisReport> plannedFor = new ArrayList<>();
        boolean applied;

        @Override
        public FixPlan plan(AnalysisReport report) {
            var fixable = report.issues().stream().filter(AnalysisIssue::autoFixable).toList();
            plannedFor.add(new AnalysisReport(fixable, report.summary(), true));
            return new FixPlan(fixable.stream()
                    .map(issue -> new FixAction(issue, Path.of("B.java"), 0, 1, "x", "", "remove"))
                    .toList());
        }

        @Override
        public FixResult apply(FixPlan plan) {
            applied = true;
            return new FixResult(plan.isEmpty() ? 0 : 1, plan.actions().size());
        }
    }
}
