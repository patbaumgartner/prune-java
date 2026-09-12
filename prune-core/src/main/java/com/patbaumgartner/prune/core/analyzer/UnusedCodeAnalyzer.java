package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisReport;

public interface UnusedCodeAnalyzer {

    AnalysisReport analyze(AnalysisConfig config);
}
