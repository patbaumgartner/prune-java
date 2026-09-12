package com.patbaumgartner.prune.core.project;

import com.patbaumgartner.prune.core.source.JavaSourceFile;

import java.nio.file.Path;
import java.util.List;

public record ScannedProject(
        Path root,
        List<JavaFile> javaFiles,
        List<ResourceFile> resources,
        List<ResourceFile> configuration,
        List<BuildFile> buildFiles
) {
    public ScannedProject {
        javaFiles = List.copyOf(javaFiles);
        resources = List.copyOf(resources);
        configuration = List.copyOf(configuration);
        buildFiles = List.copyOf(buildFiles);
    }

    public record JavaFile(JavaSourceFile source, boolean candidate, boolean testSource) {
    }

    public record ResourceFile(String relativePath, String content, boolean testSource) {
    }

    public record BuildFile(String relativePath, String content, BuildTool tool) {
    }

    public enum BuildTool {
        MAVEN,
        GRADLE
    }

    public List<JavaFile> candidates() {
        return javaFiles.stream().filter(JavaFile::candidate).toList();
    }
}
