package com.patbaumgartner.prune.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildRootsTest {

	@TempDir
	Path dir;

	@Test
	void aDirectoryWithoutABuildFileIsItsOwnRoot() throws IOException {
		write("pom.xml", aggregator("lib"));
		Files.createDirectories(dir.resolve("lib/src/main/java"));

		assertEquals(dir.resolve("lib"), BuildRoots.enclosingRoot(dir.resolve("lib")));
	}

	@Test
	void aMavenModuleClimbsToEveryPomThatListsItInTurn() throws IOException {
		write("pom.xml", aggregator("services"));
		write("services/pom.xml", aggregator("api", "impl/pom.xml"));
		write("services/api/pom.xml", plain());
		write("services/impl/pom.xml", plain());

		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("services/api")));
		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("services/impl")));
		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("services")));
	}

	@Test
	void aModuleListedByPathClimbsPastDirectoriesWithoutABuildFile() throws IOException {
		write("pom.xml", aggregator("./services/api", "${skipped}/other"));
		write("services/api/pom.xml", plain());
		write("other/pom.xml", plain());

		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("services/api")));
		assertEquals(dir.resolve("other"), BuildRoots.enclosingRoot(dir.resolve("other")));
	}

	@Test
	void aModuleInheritingFromThePomAboveItBelongsToThatBuild() throws IOException {
		write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
				+ "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging></project>");
		write("core/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>com.acme</groupId>"
				+ "<artifactId>parent</artifactId><version>1</version></parent><artifactId>core</artifactId></project>");
		write("stray/pom.xml",
				"<project><modelVersion>4.0.0</modelVersion><parent><groupId>org.springframework.boot</groupId>"
						+ "<artifactId>spring-boot-starter-parent</artifactId><version>4.1.1</version><relativePath/></parent>"
						+ "<groupId>com.example</groupId><artifactId>stray</artifactId><version>1</version></project>");

		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("core")));
		assertEquals(dir.resolve("stray"), BuildRoots.enclosingRoot(dir.resolve("stray")));
	}

	@Test
	void aNestedAggregatorIsItsOwnBuildAndSoIsTheRootAboveIt() throws IOException {
		write("pom.xml", aggregator("core"));
		write("core/pom.xml", plain());
		write("samples/pom.xml", aggregator("first", "second"));
		write("samples/first/pom.xml", plain());
		write("samples/second/pom.xml", plain());

		assertEquals(dir.resolve("samples"), BuildRoots.enclosingRoot(dir.resolve("samples/first")));
		assertEquals(dir.resolve("samples"), BuildRoots.enclosingRoot(dir.resolve("samples")));
		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("core")));
	}

	@Test
	void aGradleProjectBelongsToTheNearestSettingsFileAboveIt() throws IOException {
		write("settings.gradle", "include ':services:api'\n");
		write("build.gradle", "");
		write("services/api/build.gradle.kts", "");

		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("services/api")));
	}

	@Test
	void aGradleBuildWithItsOwnSettingsIsSeparateWhateverSitsAboveIt() throws IOException {
		write("settings.gradle.kts", "include(\"core\")\n");
		write("core/build.gradle", "");
		write("pom.xml", aggregator("core"));
		write("core/pom.xml", plain());
		write("samples/gradle-sample/settings.gradle", "rootProject.name = 'gradle-sample'\n");
		write("samples/gradle-sample/build.gradle", "");
		write("samples/pom.xml", aggregator("maven-sample"));
		write("samples/maven-sample/pom.xml", plain());

		assertEquals(dir, BuildRoots.enclosingRoot(dir.resolve("core")));
		assertEquals(dir.resolve("samples/gradle-sample"),
				BuildRoots.enclosingRoot(dir.resolve("samples/gradle-sample")));
		assertEquals(dir.resolve("samples"), BuildRoots.enclosingRoot(dir.resolve("samples/maven-sample")));
	}

	@Test
	void aPomThatCannotBeParsedAggregatesNothing() throws IOException {
		write("pom.xml",
				"<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project><modules><module>lib</module></modules></project>");
		write("lib/pom.xml", plain());
		write("broken/pom.xml", aggregator("lib") + "<oops>");
		write("broken/lib/pom.xml", plain());

		assertEquals(dir.resolve("lib"), BuildRoots.enclosingRoot(dir.resolve("lib")));
		assertEquals(dir.resolve("broken/lib"), BuildRoots.enclosingRoot(dir.resolve("broken/lib")));
	}

	private void write(String relativePath, String content) throws IOException {
		var file = dir.resolve(relativePath);
		Files.createDirectories(Objects.requireNonNull(file.getParent()));
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static String aggregator(String... modules) {
		var listed = new StringBuilder();
		for (var module : modules) {
			listed.append("<module>").append(module).append("</module>");
		}
		return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId>"
				+ "<version>1</version><packaging>pom</packaging><modules>" + listed + "</modules></project>";
	}

	private static String plain() {
		return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>";
	}

}
