package com.patbaumgartner.prune.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AnalysisConfig(Path projectRoot, List<String> includePatterns, List<String> excludePatterns,
		boolean ciMode, boolean includeTestReferences, boolean dryRun, boolean explain, Optional<Path> baseline) {

	public static final String DEFAULT_BASELINE_FILE = "prune-baseline.txt";

	public AnalysisConfig {
		Objects.requireNonNull(projectRoot, "projectRoot");
		Objects.requireNonNull(baseline, "baseline");
		includePatterns = List.copyOf(includePatterns);
		excludePatterns = List.copyOf(excludePatterns);
	}

	public static AnalysisConfig defaultFor(Path projectRoot) {
		return new AnalysisConfig(projectRoot, List.of("**/src/main/java/**"), List.of(), false, true, true, false,
				Optional.of(projectRoot.resolve(DEFAULT_BASELINE_FILE)));
	}

	public AnalysisConfig withExcludePatterns(List<String> patterns) {
		return new AnalysisConfig(projectRoot, includePatterns, patterns, ciMode, includeTestReferences, dryRun,
				explain, baseline);
	}

	public AnalysisConfig withCiMode(boolean ci) {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ci, includeTestReferences, dryRun,
				explain, baseline);
	}

	public AnalysisConfig withDryRun(boolean dry) {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, includeTestReferences, dry,
				explain, baseline);
	}

	public AnalysisConfig withIncludeTestReferences(boolean include) {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, include, dryRun, explain,
				baseline);
	}

	public AnalysisConfig withExplain(boolean explanations) {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, includeTestReferences, dryRun,
				explanations, baseline);
	}

	// The file is applied when it exists; a path that does not exist yet is not an error.
	public AnalysisConfig withBaseline(Path file) {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, includeTestReferences, dryRun,
				explain, Optional.of(file));
	}

	public AnalysisConfig withoutBaseline() {
		return new AnalysisConfig(projectRoot, includePatterns, excludePatterns, ciMode, includeTestReferences, dryRun,
				explain, Optional.empty());
	}
}
