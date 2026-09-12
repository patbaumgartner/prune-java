package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.OutputFormat;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;

final class TaskSupport {

    private TaskSupport() {
    }

    static OutputFormat parseFormat(String format) {
        try {
            return OutputFormat.parse(format);
        } catch (IllegalArgumentException exception) {
            throw new GradleException(exception.getMessage() + " (expected terminal, github, or json)");
        }
    }

    static AnalysisConfig configFor(DirectoryProperty projectRoot, ListProperty<String> excludes) {
        return AnalysisConfig.defaultFor(projectRoot.get().getAsFile().toPath()).withExcludePatterns(excludes.get());
    }

    static void logReport(Logger logger, String rendered, boolean asWarning) {
        for (String line : rendered.split("\\R")) {
            if (asWarning) {
                logger.warn(line);
            } else {
                logger.lifecycle(line);
            }
        }
    }
}
