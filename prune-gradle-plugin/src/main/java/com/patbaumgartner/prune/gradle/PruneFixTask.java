package com.patbaumgartner.prune.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

public abstract class PruneFixTask extends DefaultTask {

    @TaskAction
    public void runFix() {
        getLogger().lifecycle("prune-java fix scaffold active; no automatic rewrites are implemented yet.");
    }
}
