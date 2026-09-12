package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

@DisableCachingByDefault(because = "Its input is every source of the enclosing build, so it always runs")
public abstract class PruneBaselineTask extends DefaultTask {

	public PruneBaselineTask() {
		getOutputs().upToDateWhen(task -> false);
	}

	@Internal
	public abstract DirectoryProperty getProjectRoot();

	@Input
	public abstract ListProperty<String> getExcludes();

	@OutputFile
	public abstract RegularFileProperty getBaseline();

	@Input
	public abstract Property<Boolean> getTestReferences();

	@TaskAction
	public void runBaseline() {
		AnalysisConfig config = TaskSupport.configFor(getProjectRoot(), getExcludes(), getBaseline(),
				getTestReferences(), false);
		Path file = config.baseline().orElseThrow();
		try {
			AnalysisReport report = new ConservativeUnusedCodeAnalyzer().analyze(config.withoutBaseline());
			Baseline.write(file, report.issues());
			getLogger().lifecycle("prune-java baseline: wrote {} finding(s) to {}", report.issueCount(), file);
		}
		catch (UncheckedIOException | IOException exception) {
			throw new GradleException("prune-java could not write the baseline for " + getProjectRoot().get() + ": "
					+ exception.getMessage(), exception);
		}
	}

}
