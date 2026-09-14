package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.OutputFormat;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

final class TaskSupport {

	private TaskSupport() {
	}

	static OutputFormat parseFormat(String format) {
		try {
			return OutputFormat.parse(format);
		}
		catch (IllegalArgumentException exception) {
			throw new GradleException(exception.getMessage() + " (expected terminal, github, or json)", exception);
		}
	}

	static AnalysisConfig configFor(DirectoryProperty projectRoot, ListProperty<String> excludes,
			RegularFileProperty baseline, Property<Boolean> testReferences, boolean explain) {
		return AnalysisConfig.defaultFor(projectRoot.get().getAsFile().toPath())
			.withExcludePatterns(excludes.get())
			.withBaseline(baseline.get().getAsFile().toPath())
			.withIncludeTestReferences(testReferences.get())
			.withExplain(explain);
	}

	static void logReport(Logger logger, String rendered, boolean asWarning) {
		for (String line : rendered.lines().toList()) {
			if (asWarning) {
				logger.warn(line);
			}
			else {
				logger.lifecycle(line);
			}
		}
	}

}
