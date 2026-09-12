package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

public abstract class PruneCheckTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getProjectRoot();

    @TaskAction
    public void runCheck() {
        var analyzer = new ConservativeUnusedCodeAnalyzer();
        var report = analyzer.analyze(AnalysisConfig.defaultFor(getProjectRoot().get().getAsFile().toPath()));
        getLogger().lifecycle("prune-java check: {}", report.summary());

        if (report.issueCount() > 0) {
            throw new GradleException("prune-java found " + report.issueCount() + " unused-code issue(s).");
        }
    }
}
