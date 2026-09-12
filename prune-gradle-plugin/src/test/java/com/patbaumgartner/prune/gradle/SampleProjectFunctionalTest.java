package com.patbaumgartner.prune.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleProjectFunctionalTest {

    @Test
    void checkTaskFailsTheBuildWithTheManifestFindingsForTheGradleSampleProject(@TempDir Path projectDir) throws IOException {
        copySample("gradle-sample", projectDir);

        var result = runner(projectDir, "pruneCheck", "--console=plain").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":pruneCheck").getOutcome());
        var expected = manifest(projectDir, "unused");
        assertTrue(result.getOutput().contains("prune-java found " + expected.size() + " unused-code issue(s)."), result.getOutput());
        assertTrue(result.getOutput().contains("prune-java check: Analyzed 4 Java file(s) under " + projectDir.toRealPath()
                + ": " + expected.size() + " issue(s) found (conservative mode)."), result.getOutput());
        for (var row : expected) {
            assertTrue(result.getOutput().contains(simpleName(row.split("\\s+")[2])), row);
        }
    }

    @Test
    void checkTaskWithIgnoreFailuresLogsAJsonReportEqualToTheManifest(@TempDir Path projectDir) throws IOException {
        copySample("gradle-sample", projectDir);
        Files.writeString(projectDir.resolve("build.gradle"), Files.readString(projectDir.resolve("build.gradle"))
                + "\npruneCheck {\n    ignoreFailures = true\n    format = 'json'\n}\n");

        var result = runner(projectDir, "pruneCheck", "--console=plain").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":pruneCheck").getOutcome());
        var json = result.getOutput().lines().filter(line -> line.startsWith("{\"summary\":")).findFirst().orElseThrow();
        var reported = new ArrayList<String>();
        var matcher = Pattern.compile("\"type\":\"(\\w+)\",\"severity\":\"\\w+\",\"symbol\":\"([^\"]+)\"").matcher(json);
        while (matcher.find()) {
            reported.add(matcher.group(1) + " " + matcher.group(2));
        }
        var expected = manifest(projectDir, "unused").stream()
                .map(row -> row.split("\\s+")).map(columns -> columns[1] + " " + columns[2]).sorted().toList();
        assertEquals(expected, reported.stream().sorted().toList(), json);
    }

    @Test
    void checkTaskHonoursExcludesAndStillReportsDependencies(@TempDir Path projectDir) throws IOException {
        copySample("gradle-sample", projectDir);
        Files.writeString(projectDir.resolve("build.gradle"), Files.readString(projectDir.resolve("build.gradle"))
                + "\npruneCheck {\n    excludes = ['**/*.java']\n}\n");

        var result = runner(projectDir, "pruneCheck", "--console=plain").buildAndFail();

        assertTrue(result.getOutput().contains("prune-java check: Analyzed 0 Java file(s) under " + projectDir.toRealPath()
                + ": 1 issue(s) found (conservative mode)."), result.getOutput());
        assertTrue(result.getOutput().contains("commons-io:commons-io"), result.getOutput());
        assertTrue(result.getOutput().contains("prune-java found 1 unused-code issue(s)."), result.getOutput());
    }

    @Test
    void checkTaskReportsNoSymbolTheSampleMarksAsKept(@TempDir Path projectDir) throws IOException {
        copySample("gradle-sample", projectDir);

        var result = runner(projectDir, "pruneCheck").buildAndFail();

        for (var row : manifest(projectDir, "kept")) {
            var symbol = row.split("\\s+")[2];
            for (var line : result.getOutput().split("\\R")) {
                if (line.contains(" :: ")) {
                    assertFalse(line.contains(simpleName(symbol) + " is"), line);
                }
            }
        }
    }

    @Test
    void fixTaskRewritesTheCopiedSampleAndLeavesTheClassFindingForReview(@TempDir Path projectDir) throws IOException {
        copySample("gradle-sample", projectDir);
        var stockLevel = projectDir.resolve("src/main/java/com/example/gradlesample/StockLevel.java");
        var warehouseCodes = projectDir.resolve("src/main/java/com/example/gradlesample/WarehouseCodes.java");
        var buildScript = projectDir.resolve("build.gradle");

        var result = runner(projectDir, "pruneFix", "--configuration-cache", "--console=plain").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":pruneFix").getOutcome());
        assertTrue(result.getOutput().contains("prune-java fix: Applied 3 autofix(es) in 3 file(s); 1 issue(s) remain"), result.getOutput());
        assertTrue(result.getOutput().contains("Class ObsoleteStockExporter is never referenced"), result.getOutput());
        assertFalse(Files.readString(stockLevel).contains("legacyExportName"));
        assertTrue(Files.readString(stockLevel).contains("REORDER_THRESHOLD"));
        assertTrue(Files.readString(warehouseCodes).startsWith("package com.example.gradlesample;\n\nfinal class WarehouseCodes {"));
        assertFalse(Files.readString(buildScript).contains("commons-io"));
        assertTrue(Files.readString(buildScript).contains("slf4j-api"));
        assertTrue(Files.exists(projectDir.resolve("src/main/java/com/example/gradlesample/ObsoleteStockExporter.java")));

        var again = runner(projectDir, "pruneCheck").buildAndFail();
        assertTrue(again.getOutput().contains("prune-java found 1 unused-code issue(s)."), again.getOutput());
    }

    private static GradleRunner runner(Path projectDir, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private static List<String> manifest(Path projectDir, String verdict) throws IOException {
        return Files.readAllLines(projectDir.resolve("expected-findings.txt")).stream()
                .map(String::strip)
                .filter(line -> line.startsWith(verdict + " "))
                .toList();
    }

    private static String simpleName(String symbol) {
        if (symbol.contains("#")) {
            return symbol.substring(symbol.indexOf('#') + 1);
        }
        if (symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(':') + 1);
        }
        return symbol.substring(symbol.lastIndexOf('.') + 1);
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
