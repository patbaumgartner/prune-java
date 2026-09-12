package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

// Records every current finding so check and fix report only what appears afterwards.
@Mojo(name = "baseline", threadSafe = true)
public final class PruneBaselineMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
	private File projectDir;

	@Parameter(defaultValue = "${project.packaging}", readonly = true)
	private String packaging;

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
			getLog().info("prune-java baseline skipped.");
			return;
		}
		if (MojoSupport.isAggregator(packaging)) {
			getLog().info("prune-java baseline skipped for pom packaging; the modules are baselined individually.");
			return;
		}
		AnalysisConfig config = MojoSupport.configFor(projectDir, excludes, baseline, testReferences, false);
		Path file = config.baseline().orElseThrow();
		try {
			AnalysisReport report = new ConservativeUnusedCodeAnalyzer().analyze(config.withoutBaseline());
			Baseline.write(file, report.issues());
			getLog().info("prune-java baseline: wrote " + report.issueCount() + " finding(s) to " + file);
		}
		catch (UncheckedIOException | IOException exception) {
			throw new MojoExecutionException(
					"prune-java could not write the baseline for " + projectDir + ": " + exception.getMessage(),
					exception);
		}
	}

}
