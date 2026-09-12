package com.patbaumgartner.prune.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class PruneGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getTasks().register("pruneCheck", PruneCheckTask.class, task -> {
            task.setDescription("Analyze Java production code for unused symbols (conservative mode).");
            task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
        });

        project.getTasks().register("pruneFix", PruneFixTask.class, task ->
                task.setDescription("Apply available prune-java autofixes.")
        );
    }
}
