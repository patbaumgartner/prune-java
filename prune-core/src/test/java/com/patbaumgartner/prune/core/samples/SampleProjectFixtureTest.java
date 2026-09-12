package com.patbaumgartner.prune.core.samples;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import com.patbaumgartner.prune.core.report.SourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleProjectFixtureTest {

    private static final List<String> SAMPLES = List.of("maven-sample", "gradle-sample");

    @Test
    void everySampleDeclaresBothUnusedAndKeptGroundTruth() {
        for (var sample : SAMPLES) {
            assertFalse(symbols(sample, "unused").isEmpty(), sample);
            assertFalse(symbols(sample, "kept").isEmpty(), sample);
        }
    }

    @Test
    void everyGroundTruthLineNamesAKnownVerdictAndIssueType() {
        for (var sample : SAMPLES) {
            for (var columns : entries(sample)) {
                assertEquals(3, columns.length, sample + ": " + String.join(" ", columns));

                switch (columns[0]) {
                    case "unused" -> IssueType.valueOf(columns[1]);
                    case "kept" -> assertEquals("ANY", columns[1], columns[2]);
                    default -> throw new AssertionError(sample + ": unknown verdict " + columns[0]);
                }
            }
        }
    }

    @Test
    void analyzerReportsExactlyTheUnusedRowsOfEverySample() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var report = analyzer.analyze(AnalysisConfig.defaultFor(sampleRoot(sample)));

            assertEquals(findings(sample), findings(report), sample);
            assertTrue(report.summary().contains(sample), report.summary());
            assertTrue(report.summary().contains(report.issueCount() + " issue(s)"), report.summary());
        }
    }

    @Test
    void analyzerNeverReportsASymbolASampleMarksAsKept() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var report = analyzer.analyze(AnalysisConfig.defaultFor(sampleRoot(sample)));

            assertEquals(List.of(), keptSymbolsReportedIn(report, symbols(sample, "kept")), sample);
        }
    }

    @Test
    void everyFindingPointsAtALineThatNamesTheSymbol() throws IOException {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var root = sampleRoot(sample);
            var report = analyzer.analyze(AnalysisConfig.defaultFor(root));

            for (var issue : report.issues()) {
                var location = SourceLocation.parse(issue.location());
                var lines = Files.readAllLines(root.resolve(location.path()));
                assertTrue(location.hasLine() && location.line() <= lines.size(), issue.location());
                assertTrue(lines.get(location.line() - 1).contains(simpleName(issue.symbol())), issue.location());
            }
        }
    }

    @Test
    void onlyTheUnusedClassFindingsNeedAHuman() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var report = analyzer.analyze(AnalysisConfig.defaultFor(sampleRoot(sample)));

            for (var issue : report.issues()) {
                assertEquals(issue.type() != IssueType.UNUSED_CLASS, issue.autoFixable(), issue.symbol());
            }
        }
    }

    @Test
    void keptSymbolGuardFailsWhenAReportNamesAKeptSymbol() {
        var kept = symbols("maven-sample", "kept");
        var symbol = kept.get(0);
        var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, symbol, "A.java", "unused", false);

        var reported = keptSymbolsReportedIn(new AnalysisReport(List.of(issue), "synthetic", true), kept);

        assertEquals(List.of(symbol), reported);
    }

    private static Set<String> findings(String sample) {
        return entries(sample).stream()
                .filter(columns -> columns[0].equals("unused"))
                .map(columns -> columns[1] + " " + columns[2])
                .collect(Collectors.toSet());
    }

    private static Set<String> findings(AnalysisReport report) {
        var findings = report.issues().stream()
                .map(issue -> issue.type() + " " + issue.symbol())
                .collect(Collectors.toSet());
        assertEquals(report.issueCount(), findings.size(), "duplicate findings in " + report.issues());
        return findings;
    }

    private static String simpleName(String symbol) {
        if (symbol.contains("#")) {
            return symbol.substring(symbol.indexOf('#') + 1);
        }
        if (symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(':') + 1);
        }
        return symbol.substring(symbol.lastIndexOf('.') + 1);
    }

    private static List<String> keptSymbolsReportedIn(AnalysisReport report, Collection<String> kept) {
        return report.issues().stream()
                .map(AnalysisIssue::symbol)
                .filter(kept::contains)
                .toList();
    }

    private static List<String> symbols(String sample, String verdict) {
        return entries(sample).stream()
                .filter(columns -> columns[0].equals(verdict))
                .map(columns -> columns[2])
                .toList();
    }

    private static List<String[]> entries(String sample) {
        var manifest = sampleRoot(sample).resolve("expected-findings.txt");
        try {
            return Files.readAllLines(manifest).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("\\s+"))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read " + manifest, exception);
        }
    }

    private static Path sampleRoot(String sample) {
        for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            var candidate = directory.resolve("samples").resolve(sample);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No samples/" + sample + " above " + Path.of("").toAbsolutePath());
    }
}
