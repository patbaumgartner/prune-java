package com.patbaumgartner.prune.gradle;

import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneGradlePluginTest {

	@Test
	void registersCheckFixAndBaselineTasks() {
		var project = ProjectBuilder.builder().build();

		project.getPluginManager().apply(PruneGradlePlugin.class);

		assertNotNull(project.getTasks().findByName("pruneCheck"));
		assertNotNull(project.getTasks().findByName("pruneFix"));
		assertNotNull(project.getTasks().findByName("pruneBaseline"));
	}

	@Test
	void checkTaskDefaultsItsProjectRootToTheApplyingProject() {
		var project = ProjectBuilder.builder().build();

		project.getPluginManager().apply(PruneGradlePlugin.class);
		var task = (PruneCheckTask) project.getTasks().getByName("pruneCheck");

		assertEquals(project.getProjectDir(), task.getProjectRoot().get().getAsFile());
		assertFalse(task.getIgnoreFailures().get());
		assertEquals("terminal", task.getFormat().get());
		assertEquals(List.of(), task.getExcludes().get());
		assertEquals(new File(project.getProjectDir(), "prune-baseline.txt"), task.getBaseline().get().getAsFile());
		assertTrue(task.getTestReferences().get());
		assertFalse(task.getExplain().get());
		assertEquals("verification", task.getGroup());
	}

	@Test
	void fixTaskDefaultsItsProjectRootAndFormat() {
		var project = ProjectBuilder.builder().build();

		project.getPluginManager().apply(PruneGradlePlugin.class);
		var task = (PruneFixTask) project.getTasks().getByName("pruneFix");

		assertEquals(project.getProjectDir(), task.getProjectRoot().get().getAsFile());
		assertEquals("terminal", task.getFormat().get());
		assertEquals(List.of(), task.getExcludes().get());
		assertEquals(new File(project.getProjectDir(), "prune-baseline.txt"), task.getBaseline().get().getAsFile());
		assertTrue(task.getTestReferences().get());
	}

	@Test
	void baselineTaskDefaultsItsFileAndTestReferences() {
		var project = ProjectBuilder.builder().build();

		project.getPluginManager().apply(PruneGradlePlugin.class);
		var task = (PruneBaselineTask) project.getTasks().getByName("pruneBaseline");

		assertEquals(project.getProjectDir(), task.getProjectRoot().get().getAsFile());
		assertEquals(List.of(), task.getExcludes().get());
		assertEquals(new File(project.getProjectDir(), "prune-baseline.txt"), task.getBaseline().get().getAsFile());
		assertTrue(task.getTestReferences().get());
	}

	@Test
	void checkTaskRunsWithTheConfigurationCacheEnabled(@TempDir Path projectDir) throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'cc-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'com.patbaumgartner.prune-java' }\n",
				StandardCharsets.UTF_8);

		var result = GradleRunner.create()
			.withProjectDir(projectDir.toFile())
			.withPluginClasspath()
			.withArguments("pruneCheck", "--configuration-cache")
			.build();

		assertEquals(TaskOutcome.SUCCESS, result.task(":pruneCheck").getOutcome());
		assertTrue(result.getOutput().contains("No unused code detected (conservative mode)."), result.getOutput());
	}

	@Test
	void checkTaskRejectsAnUnknownFormat(@TempDir Path projectDir) throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'format-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"),
				"plugins { id 'com.patbaumgartner.prune-java' }\npruneCheck { format = 'xml' }\n",
				StandardCharsets.UTF_8);

		var result = GradleRunner.create()
			.withProjectDir(projectDir.toFile())
			.withPluginClasspath()
			.withArguments("pruneCheck")
			.buildAndFail();

		assertEquals(TaskOutcome.FAILED, result.task(":pruneCheck").getOutcome());
		assertTrue(result.getOutput().contains("Unsupported format: xml (expected terminal, github, json, or sarif)"),
				result.getOutput());
	}

	@Test
	void baselineTaskRecordsTheFindingsAndCheckThenPassesWithThemSuppressed(@TempDir Path projectDir)
			throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'baseline-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'com.patbaumgartner.prune-java' }\n",
				StandardCharsets.UTF_8);
		writeSource(projectDir, "src/main/java/com/example/Dead.java",
				"package com.example;\n\nfinal class Dead {\n}\n");

		var before = runner(projectDir, "pruneCheck").buildAndFail();
		var baseline = runner(projectDir, "pruneBaseline", "--configuration-cache").build();
		var after = runner(projectDir, "pruneCheck", "--configuration-cache").build();

		assertEquals(TaskOutcome.FAILED, before.task(":pruneCheck").getOutcome());
		assertEquals(TaskOutcome.SUCCESS, baseline.task(":pruneBaseline").getOutcome());
		assertTrue(baseline.getOutput()
			.contains("prune-java baseline: wrote 1 finding(s) to "
					+ projectDir.toRealPath().resolve("prune-baseline.txt")),
				baseline.getOutput());
		assertTrue(Files.readString(projectDir.resolve("prune-baseline.txt"), StandardCharsets.UTF_8)
			.endsWith("UNUSED_CLASS com.example.Dead\n"));
		assertEquals(TaskOutcome.SUCCESS, after.task(":pruneCheck").getOutcome());
		assertTrue(
				after.getOutput()
					.contains(": 0 issue(s) found (conservative mode). 1 issue(s) suppressed by prune-baseline.txt."),
				after.getOutput());
	}

	@Test
	void baselineTaskRunsAgainWhenTheSourcesChangeEvenThoughItsOnlyDeclaredInputsDidNot(@TempDir Path projectDir)
			throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'rerun-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'com.patbaumgartner.prune-java' }\n",
				StandardCharsets.UTF_8);
		writeSource(projectDir, "src/main/java/com/example/Dead.java",
				"package com.example;\n\nfinal class Dead {\n}\n");

		runner(projectDir, "pruneBaseline").build();
		writeSource(projectDir, "src/main/java/com/example/Other.java",
				"package com.example;\n\nfinal class Other {\n}\n");
		var second = runner(projectDir, "pruneBaseline").build();

		assertEquals(TaskOutcome.SUCCESS, second.task(":pruneBaseline").getOutcome());
		assertTrue(Files.readString(projectDir.resolve("prune-baseline.txt"), StandardCharsets.UTF_8)
			.contains("UNUSED_CLASS com.example.Other\n"));
	}

	@Test
	void tasksHonourAnExplicitBaselineFileAndTestReferences(@TempDir Path projectDir) throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'options-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"), """
				plugins { id 'com.patbaumgartner.prune-java' }
				pruneCheck {
				    baseline = layout.projectDirectory.file('config/accepted.txt')
				    testReferences = false
				    explain = true
				}
				pruneBaseline {
				    baseline = layout.projectDirectory.file('config/accepted.txt')
				    testReferences = false
				}
				""", StandardCharsets.UTF_8);
		writeSource(projectDir, "src/main/java/com/example/Helper.java",
				"package com.example;\n\nfinal class Helper {\n}\n");
		writeSource(projectDir, "src/test/java/com/example/HelperTest.java",
				"package com.example;\n\nclass HelperTest {\n    Helper h = new Helper();\n}\n");
		Files.createDirectories(projectDir.resolve("config"));

		var before = runner(projectDir, "pruneCheck").buildAndFail();
		runner(projectDir, "pruneBaseline").build();
		var after = runner(projectDir, "pruneCheck").build();

		assertTrue(before.getOutput().contains("Class Helper is only referenced from tests"), before.getOutput());
		assertTrue(Files.readString(projectDir.resolve("config/accepted.txt"), StandardCharsets.UTF_8)
			.endsWith("UNUSED_CLASS com.example.Helper\n"));
		assertTrue(after.getOutput()
			.contains(
					"+ [KEPT] src/main/java/com/example/Helper.java:3:13 :: accepted in config/accepted.txt [baseline]"),
				after.getOutput());
	}

	@Test
	void checkTaskTurnsAMalformedBaselineIntoABuildFailure(@TempDir Path projectDir) throws IOException {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'malformed-probe'\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'com.patbaumgartner.prune-java' }\n",
				StandardCharsets.UTF_8);
		Files.writeString(projectDir.resolve("prune-baseline.txt"), "UNUSED_CLASS\n", StandardCharsets.UTF_8);

		var result = runner(projectDir, "pruneCheck").buildAndFail();

		assertEquals(TaskOutcome.FAILED, result.task(":pruneCheck").getOutcome());
		assertTrue(
				result.getOutput()
					.contains("prune-baseline.txt:1: expected \"TYPE symbol\" but found \"UNUSED_CLASS\""),
				result.getOutput());
	}

	private static GradleRunner runner(Path projectDir, String... arguments) {
		return GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath().withArguments(arguments);
	}

	private static void writeSource(Path projectDir, String relativePath, String content) throws IOException {
		var file = projectDir.resolve(relativePath);
		Files.createDirectories(Objects.requireNonNull(file.getParent()));
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

}
