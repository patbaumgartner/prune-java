package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeUnusedCodeAnalyzerTest {

    @Test
    void returnsConservativeEmptyReportForNow() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();

        var report = analyzer.analyze(AnalysisConfig.defaultFor(Path.of(".")));

        assertTrue(report.conservativeMode());
        assertEquals(0, report.issueCount());
        assertTrue(report.summary().contains("Analyzer scaffold active"));
    }
}
