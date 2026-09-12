package com.patbaumgartner.prune.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AnalysisConfig(
        Path projectRoot,
        List<String> includePatterns,
        List<String> excludePatterns,
        boolean ciMode,
        boolean includeTestReferences,
        boolean dryRun
) {
    public AnalysisConfig {
        Objects.requireNonNull(projectRoot, "projectRoot");
        includePatterns = List.copyOf(includePatterns);
        excludePatterns = List.copyOf(excludePatterns);
    }

    public static AnalysisConfig defaultFor(Path projectRoot) {
        return new AnalysisConfig(projectRoot, List.of("**/src/main/java/**"), List.of(), false, true, true);
    }

    public AnalysisConfig withExcludePatterns(List<String> patterns) {
        return new AnalysisConfig(projectRoot, includePatterns, patterns, ciMode, includeTestReferences, dryRun);
    }

    public AnalysisConfig withCiMode(boolean ci) {
        return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ci, includeTestReferences, dryRun);
    }

    public AnalysisConfig withDryRun(boolean dry) {
        return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, includeTestReferences, dry);
    }
}
