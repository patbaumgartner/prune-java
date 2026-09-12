package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "fix", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class PruneFixMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
	private File projectDir;

	@Parameter(defaultValue = "${project.packaging}", readonly = true)
	private String packaging;

	@Parameter(property = "prune.format", defaultValue = "terminal")
	private String format;

	@Parameter(property = "prune.excludes")
	private List<String> excludes;

	@Parameter(property = "prune.baseline")
	private File baseline;

	@Parameter(property = "prune.testReferences", defaultValue = "true")
	private boolean testReferences;

	@Parameter(property = "prune.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException {
		if (skip) {
			getLog().info("prune-java fix skipped.");
			return;
		}
		if (MojoSupport.isAggregator(packaging)) {
			getLog().info("prune-java fix skipped for pom packaging; the modules are fixed individually.");
			return;
		}
		OutputFormat outputFormat = MojoSupport.parseFormat(format);
		Path root = projectDir.toPath();
		try {
			AnalysisReport report = new ConservativeUnusedCodeAnalyzer()
				.analyze(MojoSupport.configFor(projectDir, excludes, baseline, testReferences, false));
			AnalysisReport remaining = new SourceAutofixEngine(root).fix(report);
			getLog().info("prune-java fix: " + remaining.summary());
			MojoSupport.logReport(getLog(), new ReportRenderer().render(remaining, outputFormat),
					remaining.issueCount() > 0);
		}
		catch (UncheckedIOException | IllegalStateException | IllegalArgumentException exception) {
			throw new MojoExecutionException("prune-java could not fix " + projectDir + ": " + exception.getMessage(),
					exception);
		}
	}

}
