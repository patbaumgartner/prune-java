package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.List;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class PruneCheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDir;

    @Parameter(defaultValue = "${project.packaging}", readonly = true)
    private String packaging;

    @Parameter(property = "prune.failOnIssues", defaultValue = "true")
    private boolean failOnIssues;

    @Parameter(property = "prune.format", defaultValue = "terminal")
    private String format;

    @Parameter(property = "prune.excludes")
    private List<String> excludes;

    @Parameter(property = "prune.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("prune-java check skipped.");
            return;
        }
        if (MojoSupport.isAggregator(packaging)) {
            getLog().info("prune-java check skipped for pom packaging; the modules are analyzed individually.");
            return;
        }
        OutputFormat outputFormat = MojoSupport.parseFormat(format);
        AnalysisReport report;
        try {
            report = new ConservativeUnusedCodeAnalyzer().analyze(MojoSupport.configFor(projectDir, excludes));
        } catch (UncheckedIOException exception) {
            throw new MojoExecutionException("prune-java could not analyze " + projectDir, exception);
        }
        getLog().info("prune-java check: " + report.summary());
        MojoSupport.logReport(getLog(), new ReportRenderer().render(report, outputFormat), report.issueCount() > 0);

        if (report.issueCount() > 0 && failOnIssues) {
            throw new MojoFailureException("prune-java found " + report.issueCount() + " unused-code issue(s).");
        }
    }
}
