package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.UncheckedIOException;
import java.nio.file.Path;

public abstract class PruneFixTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getProjectRoot();

    @Input
    public abstract Property<String> getFormat();

    @Input
    public abstract ListProperty<String> getExcludes();

    @TaskAction
    public void runFix() {
        OutputFormat format = TaskSupport.parseFormat(getFormat().get());
        Path root = getProjectRoot().get().getAsFile().toPath();
        try {
            AnalysisReport report = new ConservativeUnusedCodeAnalyzer().analyze(TaskSupport.configFor(getProjectRoot(), getExcludes()));
            AnalysisReport remaining = new SourceAutofixEngine(root).fix(report);
            getLogger().lifecycle("prune-java fix: {}", remaining.summary());
            TaskSupport.logReport(getLogger(), new ReportRenderer().render(remaining, format), remaining.issueCount() > 0);
        } catch (UncheckedIOException | IllegalStateException exception) {
            throw new GradleException("prune-java could not fix " + root + ": " + exception.getMessage(), exception);
        }
    }
}
