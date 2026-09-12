package com.patbaumgartner.prune.core.config;

import com.patbaumgartner.prune.core.dependency.MavenPomParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BuildRoots {

	private static final List<String> BUILD_FILES = List.of("pom.xml", "settings.gradle", "settings.gradle.kts",
			"build.gradle", "build.gradle.kts");

	private static final List<String> GRADLE_SETTINGS = List.of("settings.gradle", "settings.gradle.kts");

	private static final List<String> GRADLE_BUILD_SCRIPTS = List.of("build.gradle", "build.gradle.kts");

	private BuildRoots() {
	}

	// The topmost ancestor whose build includes the analyzed root, one membership at a
	// time: a
	// Maven module belongs to the pom above it that lists it as a module or is its
	// parent, a
	// Gradle project to the nearest settings file above it unless it has its own. A pom
	// that
	// merely sits above a directory does not make that directory part of its build, so a
	// nested
	// aggregator (an examples tree with its own pom) is analyzed on its own, and so is
	// the build
	// it sits in.
	public static Path enclosingRoot(Path projectRoot) {
		Path root = projectRoot.toAbsolutePath().normalize();
		if (!isBuildRoot(root)) {
			return root;
		}
		Path top = root;
		for (Path ancestor = root.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
			if (aggregatesOrParents(ancestor, top) || isGradleBuildOf(ancestor, top)) {
				top = ancestor;
			}
		}
		return top;
	}

	public static boolean isBuildRoot(Path directory) {
		return hasAny(directory, BUILD_FILES);
	}

	private static boolean aggregatesOrParents(Path ancestor, Path module) {
		Path pom = ancestor.resolve("pom.xml");
		if (!Files.isRegularFile(pom)) {
			return false;
		}
		MavenPomParser.Structure structure = MavenPomParser.structure(read(pom));
		for (String declared : structure.modules()) {
			if (declared.contains("${")) {
				continue;
			}
			// A module is listed by its directory or by the path of its pom.
			Path listed = ancestor.resolve(declared).normalize();
			if (Files.isRegularFile(listed)) {
				listed = listed.getParent();
			}
			if (module.equals(listed)) {
				return true;
			}
		}
		Path modulePom = module.resolve("pom.xml");
		return Files.isRegularFile(modulePom) && structure.isParentOf(MavenPomParser.structure(read(modulePom)));
	}

	// Gradle resolves a project against the nearest settings file above it; a directory
	// with its
	// own settings file is a separate build, however deeply it is nested.
	private static boolean isGradleBuildOf(Path ancestor, Path project) {
		return hasAny(ancestor, GRADLE_SETTINGS) && hasAny(project, GRADLE_BUILD_SCRIPTS)
				&& !hasAny(project, GRADLE_SETTINGS);
	}

	private static boolean hasAny(Path directory, List<String> names) {
		for (String name : names) {
			if (Files.isRegularFile(directory.resolve(name))) {
				return true;
			}
		}
		return false;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException unreadable) {
			return "";
		}
	}

}
