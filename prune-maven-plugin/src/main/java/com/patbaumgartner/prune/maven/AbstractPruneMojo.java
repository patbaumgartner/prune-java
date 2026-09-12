package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.List;

abstract class AbstractPruneMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
	File projectDir;

	@Parameter(defaultValue = "${project.packaging}", readonly = true)
	String packaging;

	@Parameter(property = "prune.excludes")
	@Nullable List<String> excludes;

	@Parameter(property = "prune.baseline")
	@Nullable File baseline;

	@Parameter(property = "prune.testReferences", defaultValue = "true")
	boolean testReferences;

	@Parameter(property = "prune.skip", defaultValue = "false")
	boolean skip;

	@Override
	public final void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("prune-java " + goal() + " skipped.");
			return;
		}
		if (MojoSupport.isAggregator(packaging)) {
			getLog().info("prune-java " + goal() + " skipped for pom packaging; the modules are " + verb()
					+ " individually.");
			return;
		}
		run();
	}

	abstract String goal();

	abstract String verb();

	abstract void run() throws MojoExecutionException, MojoFailureException;

	final AnalysisConfig configFor(boolean explain) {
		return MojoSupport.configFor(projectDir, excludes, baseline, testReferences, explain);
	}

}
