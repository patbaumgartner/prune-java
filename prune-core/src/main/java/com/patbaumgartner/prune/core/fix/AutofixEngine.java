package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.report.AnalysisReport;

public interface AutofixEngine {

    FixPlan plan(AnalysisReport report);

    default void apply(FixPlan plan) {
        // Placeholder for source rewrite integration.
    }
}
