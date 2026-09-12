package com.patbaumgartner.prune.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.List;

public final class PruneGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getTasks().register("pruneCheck", PruneCheckTask.class, task -> {
            task.setDescription("Analyze Java production code for unused symbols (conservative mode).");
            task.setGroup("verification");
            task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
            task.getIgnoreFailures().convention(false);
            task.getFormat().convention("terminal");
            task.getExcludes().convention(List.of());
        });

        project.getTasks().register("pruneFix", PruneFixTask.class, task -> {
            task.setDescription("Apply available prune-java autofixes.");
            task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
            task.getFormat().convention("terminal");
            task.getExcludes().convention(List.of());
        });
    }
}
