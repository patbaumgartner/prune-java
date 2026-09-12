package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.UncheckedIOException;

@Mojo(name = "fix", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class PruneFixMojo extends AbstractPruneMojo {

	@Parameter(property = "prune.format", defaultValue = "terminal")
	String format;

	@Override
	String goal() {
		return "fix";
	}

	@Override
	String verb() {
		return "fixed";
	}

	@Override
	void run() throws MojoExecutionException {
		OutputFormat outputFormat = MojoSupport.parseFormat(format);
		try {
			AnalysisReport report = new ConservativeUnusedCodeAnalyzer().analyze(configFor(false));
			AnalysisReport remaining = new SourceAutofixEngine(projectDir.toPath()).fix(report);
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
