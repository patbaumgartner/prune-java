package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;

import java.util.List;

public final class PruneGradlePlugin implements Plugin<Project> {

	@Override
	public void apply(Project project) {
		RegularFile defaultBaseline = project.getLayout()
			.getProjectDirectory()
			.file(AnalysisConfig.DEFAULT_BASELINE_FILE);

		project.getTasks().register("pruneCheck", PruneCheckTask.class, task -> {
			task.setDescription("Analyze Java production code for unused symbols (conservative mode).");
			task.setGroup("verification");
			task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
			task.getIgnoreFailures().convention(false);
			task.getFormat().convention("terminal");
			task.getExcludes().convention(List.of());
			task.getBaseline().convention(defaultBaseline);
			task.getTestReferences().convention(true);
			task.getExplain().convention(false);
		});

		project.getTasks().register("pruneFix", PruneFixTask.class, task -> {
			task.setDescription("Apply available prune-java autofixes.");
			task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
			task.getFormat().convention("terminal");
			task.getExcludes().convention(List.of());
			task.getBaseline().convention(defaultBaseline);
			task.getTestReferences().convention(true);
		});

		project.getTasks().register("pruneBaseline", PruneBaselineTask.class, task -> {
			task.setDescription("Record every current prune-java finding in the baseline file.");
			task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
			task.getExcludes().convention(List.of());
			task.getBaseline().convention(defaultBaseline);
			task.getTestReferences().convention(true);
		});
	}

}
