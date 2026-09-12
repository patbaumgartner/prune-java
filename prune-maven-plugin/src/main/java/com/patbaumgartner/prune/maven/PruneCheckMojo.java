package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.UncheckedIOException;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class PruneCheckMojo extends AbstractPruneMojo {

	@Parameter(property = "prune.failOnIssues", defaultValue = "true")
	boolean failOnIssues;

	@Parameter(property = "prune.format", defaultValue = "terminal")
	String format;

	@Parameter(property = "prune.explain", defaultValue = "false")
	boolean explain;

	@Override
	String goal() {
		return "check";
	}

	@Override
	String verb() {
		return "analyzed";
	}

	@Override
	void run() throws MojoExecutionException, MojoFailureException {
		OutputFormat outputFormat = MojoSupport.parseFormat(format);
		AnalysisReport report;
		try {
			report = new ConservativeUnusedCodeAnalyzer().analyze(configFor(explain));
		}
		catch (UncheckedIOException | IllegalArgumentException exception) {
			throw new MojoExecutionException(
					"prune-java could not analyze " + projectDir + ": " + exception.getMessage(), exception);
		}
		getLog().info("prune-java check: " + report.summary());
		MojoSupport.logReport(getLog(), new ReportRenderer().render(report, outputFormat), report.issueCount() > 0);

		if (report.issueCount() > 0 && failOnIssues) {
			throw new MojoFailureException("prune-java found " + report.issueCount() + " unused-code issue(s).");
		}
	}

}
