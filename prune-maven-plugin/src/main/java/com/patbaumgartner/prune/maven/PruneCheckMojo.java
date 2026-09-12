package com.patbaumgartner.prune.maven;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class PruneCheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDir;

    @Override
    public void execute() throws MojoFailureException {
        var analyzer = new ConservativeUnusedCodeAnalyzer();
        var report = analyzer.analyze(AnalysisConfig.defaultFor(projectDir.toPath()));
        getLog().info("prune-java check: " + report.summary());

        if (report.issueCount() > 0) {
            throw new MojoFailureException("prune-java found " + report.issueCount() + " unused-code issue(s).");
        }
    }
}
