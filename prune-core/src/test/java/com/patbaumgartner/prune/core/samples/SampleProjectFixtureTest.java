package com.patbaumgartner.prune.core.samples;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

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
    void analyzerNeverReportsASymbolASampleMarksAsKept() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var report = analyzer.analyze(AnalysisConfig.defaultFor(sampleRoot(sample)));

            assertEquals(List.of(), keptSymbolsReportedIn(report, symbols(sample, "kept")), sample);
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

    @Test
    void analyzerScaffoldStillReportsNothingForEverySample() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        for (var sample : SAMPLES) {
            var report = analyzer.analyze(AnalysisConfig.defaultFor(sampleRoot(sample)));

            assertEquals(0, report.issueCount(), sample);
            assertTrue(report.summary().contains(sample), report.summary());
        }
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
