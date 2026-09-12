package com.patbaumgartner.prune.gradle;

import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneGradlePluginTest {

    @Test
    void registersCheckAndFixTasks() {
        var project = ProjectBuilder.builder().build();

        project.getPluginManager().apply(PruneGradlePlugin.class);

        assertNotNull(project.getTasks().findByName("pruneCheck"));
        assertNotNull(project.getTasks().findByName("pruneFix"));
    }

    @Test
    void checkTaskDefaultsItsProjectRootToTheApplyingProject() {
        var project = ProjectBuilder.builder().build();

        project.getPluginManager().apply(PruneGradlePlugin.class);
        var task = (PruneCheckTask) project.getTasks().getByName("pruneCheck");

        assertEquals(project.getProjectDir(), task.getProjectRoot().get().getAsFile());
        assertEquals(Boolean.FALSE, task.getIgnoreFailures().get());
        assertEquals("terminal", task.getFormat().get());
        assertEquals(java.util.List.of(), task.getExcludes().get());
        assertEquals("verification", task.getGroup());
    }

    @Test
    void fixTaskDefaultsItsProjectRootAndFormat() {
        var project = ProjectBuilder.builder().build();

        project.getPluginManager().apply(PruneGradlePlugin.class);
        var task = (PruneFixTask) project.getTasks().getByName("pruneFix");

        assertEquals(project.getProjectDir(), task.getProjectRoot().get().getAsFile());
        assertEquals("terminal", task.getFormat().get());
        assertEquals(java.util.List.of(), task.getExcludes().get());
    }

    @Test
    void checkTaskRunsWithTheConfigurationCacheEnabled(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'cc-probe'\n");
        Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'com.patbaumgartner.prune-java' }\n");

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
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'format-probe'\n");
        Files.writeString(projectDir.resolve("build.gradle"),
                "plugins { id 'com.patbaumgartner.prune-java' }\npruneCheck { format = 'xml' }\n");

        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("pruneCheck")
                .buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":pruneCheck").getOutcome());
        assertTrue(result.getOutput().contains("Unsupported format: xml (expected terminal, github, or json)"), result.getOutput());
    }
}
