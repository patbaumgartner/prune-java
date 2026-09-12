package com.patbaumgartner.prune.core.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BuildRoots {

    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");

    private BuildRoots() {
    }

    // The topmost contiguous ancestor that is itself a build root: a module inside a Maven reactor
    // or Gradle multi-project build gets its siblings as reference scope, so code they use is kept.
    public static Path enclosingRoot(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!isBuildRoot(root)) {
            return root;
        }
        Path top = root;
        for (Path parent = root.getParent(); parent != null && isBuildRoot(parent); parent = parent.getParent()) {
            top = parent;
        }
        return top;
    }

    public static boolean isBuildRoot(Path directory) {
        for (String name : BUILD_FILES) {
            if (Files.isRegularFile(directory.resolve(name))) {
                return true;
            }
        }
        return false;
    }
}
