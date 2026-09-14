package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

// Records every current finding so check and fix report only what appears afterwards.
@Mojo(name = "baseline", threadSafe = true)
public final class PruneBaselineMojo extends AbstractPruneMojo {

	@Override
	String goal() {
		return "baseline";
	}

	@Override
	String verb() {
		return "baselined";
	}

	@Override
	void run() throws MojoExecutionException {
		AnalysisConfig config = configFor(false);
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
