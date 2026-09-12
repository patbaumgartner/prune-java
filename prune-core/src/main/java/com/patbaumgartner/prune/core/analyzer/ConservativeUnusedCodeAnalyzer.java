package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisReport;

import java.util.List;

public final class ConservativeUnusedCodeAnalyzer implements UnusedCodeAnalyzer {

    @Override
    public AnalysisReport analyze(AnalysisConfig config) {
        String summary = "Analyzer scaffold active for " + config.projectRoot()
                + "; detection for classes/methods/fields will be added in next iterations.";

        return new AnalysisReport(List.of(), summary, true);
    }
}
