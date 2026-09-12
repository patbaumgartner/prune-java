package com.patbaumgartner.prune.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleProjectFunctionalTest {

    @Test
    void checkTaskAnalysesTheGradleSampleProject(@TempDir Path projectDir) throws IOException {
        var result = runSampleTask(projectDir, "pruneCheck");

        assertEquals(TaskOutcome.SUCCESS, result.task(":pruneCheck").getOutcome());
        assertTrue(result.getOutput().contains("prune-java check: Analyzer scaffold active for "
                + projectDir.toRealPath()), result.getOutput());
    }

    @Test
    void fixTaskRunsAgainstTheGradleSampleProject(@TempDir Path projectDir) throws IOException {
        var result = runSampleTask(projectDir, "pruneFix");

        assertEquals(TaskOutcome.SUCCESS, result.task(":pruneFix").getOutcome());
        assertTrue(result.getOutput().contains("prune-java fix scaffold active"), result.getOutput());
    }

    @Test
    void checkTaskReportsNoSymbolTheSampleMarksAsKept(@TempDir Path projectDir) throws IOException {
        var result = runSampleTask(projectDir, "pruneCheck");

        for (var line : Files.readAllLines(projectDir.resolve("expected-findings.txt"))) {
            if (line.startsWith("kept ")) {
                var symbol = line.split("\\s+")[2];
                assertEquals(-1, result.getOutput().indexOf(symbol), symbol);
            }
        }
    }

    private static BuildResult runSampleTask(Path projectDir, String task) throws IOException {
        copySample("gradle-sample", projectDir);

        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(task)
                .build();
    }

    private static void copySample(String sample, Path target) throws IOException {
        var source = sampleRoot(sample);

        try (Stream<Path> tree = Files.walk(source)) {
            for (var path : tree.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        applyPluginUnderTest(target.resolve("build.gradle"));
    }

    /**
     * TestKit's {@code withPluginClasspath()} resolves the plugin under test only through
     * the {@code plugins {}} block, so the id has to go inside the sample's existing one.
     * A legacy {@code apply plugin:} line would fail with "plugin not found".
     */
    private static void applyPluginUnderTest(Path buildFile) throws IOException {
        var script = Files.readString(buildFile);
        if (!script.contains("plugins {")) {
            throw new IllegalStateException("No plugins block to extend in " + buildFile);
        }
        Files.writeString(buildFile, script.replace("plugins {",
                "plugins {" + System.lineSeparator() + "    id 'com.patbaumgartner.prune-java'"));
    }

    private static Path sampleRoot(String sample) {
        for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            var candidate = directory.resolve("samples").resolve(sample);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No samples/" + sample + " above " + Path.of("").toAbsolutePath());
    }
}
