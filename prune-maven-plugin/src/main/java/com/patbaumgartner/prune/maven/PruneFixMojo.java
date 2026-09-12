package com.patbaumgartner.prune.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "fix", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class PruneFixMojo extends AbstractMojo {

    @Override
    public void execute() {
        getLog().info("prune-java fix scaffold active; no automatic rewrites are implemented yet.");
    }
}
