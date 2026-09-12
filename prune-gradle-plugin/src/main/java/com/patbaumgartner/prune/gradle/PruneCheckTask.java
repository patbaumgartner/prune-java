package com.patbaumgartner.prune.gradle;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
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
import org.gradle.work.DisableCachingByDefault;

import java.io.UncheckedIOException;

@DisableCachingByDefault(because = "Reports to the build log and declares no outputs")
public abstract class PruneCheckTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getProjectRoot();

    @Input
    public abstract Property<Boolean> getIgnoreFailures();

    @Input
    public abstract Property<String> getFormat();

    @Input
    public abstract ListProperty<String> getExcludes();

    @TaskAction
    public void runCheck() {
        OutputFormat format = TaskSupport.parseFormat(getFormat().get());
        AnalysisReport report;
        try {
            report = new ConservativeUnusedCodeAnalyzer().analyze(TaskSupport.configFor(getProjectRoot(), getExcludes()));
        } catch (UncheckedIOException exception) {
            throw new GradleException("prune-java could not analyze " + getProjectRoot().get(), exception);
        }
        getLogger().lifecycle("prune-java check: {}", report.summary());
        TaskSupport.logReport(getLogger(), new ReportRenderer().render(report, format), report.issueCount() > 0);

        if (report.issueCount() > 0 && !getIgnoreFailures().get()) {
            throw new GradleException("prune-java found " + report.issueCount() + " unused-code issue(s).");
        }
    }
}
