package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.OutputFormat;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.util.List;

final class MojoSupport {

    private MojoSupport() {
    }

    static OutputFormat parseFormat(String format) throws MojoExecutionException {
        try {
            return OutputFormat.parse(format);
        } catch (IllegalArgumentException exception) {
            throw new MojoExecutionException(exception.getMessage() + " (expected terminal, github, or json)");
        }
    }

    static AnalysisConfig configFor(File projectDir, List<String> excludes) {
        return AnalysisConfig.defaultFor(projectDir.toPath()).withExcludePatterns(excludes == null ? List.of() : excludes);
    }

    // An aggregator has no sources of its own; analyzing it would repeat every module's findings.
    static boolean isAggregator(String packaging) {
        return "pom".equals(packaging);
    }

    static void logReport(Log log, String rendered, boolean asWarning) {
        for (String line : rendered.split("\\R")) {
            if (asWarning) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }
}
