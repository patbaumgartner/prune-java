package com.patbaumgartner.prune.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneMojoTest {

	private static final String DEAD = "package com.example;\n\nfinal class Dead {\n    private int orphan;\n}\n";

	@TempDir
	Path tempDir;

	@Test
	void checkMojoUsesProjectDirectoryInSummaryAndPassesOnACleanProject() throws Exception {
		write("src/main/java/com/example/Live.java", "package com.example;\n\npublic class Live {\n}\n");
		var mojo = checkMojo(true, "terminal");
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertTrue(log.messages.contains("prune-java check: Analyzed 1 Java file(s) under " + tempDir
				+ ": 0 issue(s) found (conservative mode)."), log.messages.toString());
		assertTrue(log.messages.contains("No unused code detected (conservative mode)."), log.messages.toString());
		assertEquals(List.of(), log.warnings);
	}

	@Test
	void checkMojoFailsTheBuildWithTheIssueCountAndLogsEveryFindingAsAWarning() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		var mojo = checkMojo(true, "terminal");
		var log = new CapturingLog();
		mojo.setLog(log);

		var failure = assertThrows(MojoFailureException.class, mojo::execute);

		assertEquals("prune-java found 1 unused-code issue(s).", failure.getMessage());
		assertEquals(List.of("- [WARNING] src/main/java/com/example/Dead.java:3:13 :: Class Dead is never referenced",
				"Summary: Analyzed 1 Java file(s) under " + tempDir + ": 1 issue(s) found (conservative mode)."),
				log.warnings);
	}

	@Test
	void checkMojoWithFailOnIssuesDisabledOnlyLogsAndHonoursTheFormat() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		var mojo = checkMojo(false, "json");
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals(1, log.warnings.size());
		assertTrue(log.warnings.get(0).startsWith("{\"summary\":\"Analyzed 1 Java file(s)"), log.warnings.get(0));
		assertTrue(log.warnings.get(0).contains("\"symbol\":\"com.example.Dead\""), log.warnings.get(0));
	}

	@Test
	void checkMojoRejectsAnUnknownFormatBeforeAnalyzing() throws Exception {
		var mojo = checkMojo(true, "xml");
		mojo.setLog(new CapturingLog());

		var failure = assertThrows(MojoExecutionException.class, mojo::execute);

		assertEquals("Unsupported format: xml (expected terminal, github, or json)", failure.getMessage());
	}

	@Test
	void checkMojoSkipsWhenAsked() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		var mojo = checkMojo(true, "terminal");
		setPrivateField(mojo, "skip", true);
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals(List.of("prune-java check skipped."), log.messages);
	}

	@Test
	void checkMojoSkipsAggregatorModulesSoFindingsAreReportedOncePerModule() throws Exception {
		write("core/src/main/java/com/example/Dead.java", DEAD);
		var mojo = checkMojo(true, "terminal");
		setPrivateField(mojo, "packaging", "pom");
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals(List.of("prune-java check skipped for pom packaging; the modules are analyzed individually."),
				log.messages);
		assertEquals(List.of(), log.warnings);
	}

	@Test
	void checkMojoHonoursExcludePatterns() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		write("src/main/java/com/example/generated/Stub.java",
				"package com.example.generated;\n\nfinal class Stub {\n}\n");
		var mojo = checkMojo(true, "terminal");
		setPrivateField(mojo, "excludes", List.of("**/generated/**"));
		var log = new CapturingLog();
		mojo.setLog(log);

		var failure = assertThrows(MojoFailureException.class, mojo::execute);

		assertEquals("prune-java found 1 unused-code issue(s).", failure.getMessage());
		assertTrue(log.warnings.get(0).contains("Dead.java"), log.warnings.toString());
		assertFalse(log.warnings.toString().contains("Stub"), log.warnings.toString());
	}

	@Test
	void pluginDescriptorDeclaresEveryGoalAndAnInjectableProjectDir() throws Exception {
		String descriptor;
		try (var in = PruneMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
			assertNotNull(in, "plugin.xml is generated into target/classes during the build");
			descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertTrue(descriptor.contains("<goal>check</goal>"), descriptor);
		assertTrue(descriptor.contains("<goal>fix</goal>"), descriptor);
		assertTrue(descriptor.contains("<goal>baseline</goal>"), descriptor);
		assertTrue(descriptor.contains("default-value=\"true\">${prune.failOnIssues}</failOnIssues>"), descriptor);
		assertTrue(descriptor.contains("default-value=\"terminal\">${prune.format}</format>"), descriptor);
		assertTrue(descriptor.contains("default-value=\"false\">${prune.skip}</skip>"), descriptor);
		assertTrue(descriptor.contains("default-value=\"true\">${prune.testReferences}</testReferences>"), descriptor);
		assertTrue(descriptor.contains("default-value=\"false\">${prune.explain}</explain>"), descriptor);
		assertTrue(descriptor.contains("<baseline implementation=\"java.io.File\">${prune.baseline}</baseline>"),
				descriptor);
		assertTrue(descriptor.contains("<excludes implementation=\"java.util.List\">${prune.excludes}</excludes>"),
				descriptor);
		assertTrue(
				descriptor.contains(
						"<packaging implementation=\"java.lang.String\" default-value=\"${project.packaging}\"/>"),
				descriptor);

		var projectDir = descriptor.lines().filter(line -> line.contains("<projectDir")).findFirst().orElseThrow();

		assertTrue(projectDir.contains("default-value=\"${project.basedir}\""), projectDir);
		// ${project.basedir} evaluates to a File; Maven cannot inject it into a String
		// field.
		assertTrue(projectDir.contains("implementation=\"java.io.File\""), projectDir);
	}

	@Test
	void fixMojoRewritesSourcesAndLogsWhatRemains() throws Exception {
		var service = write("src/main/java/com/example/Service.java",
				"package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		write("src/main/java/com/example/Dead.java", DEAD);
		var mojo = fixMojo();
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals("package com.example;\n\npublic class Service {\n}\n", Files.readString(service));
		assertTrue(Files.readString(tempDir.resolve("src/main/java/com/example/Dead.java"))
			.contains("private int orphan"));
		assertTrue(
				log.messages.contains(
						"prune-java fix: Applied 1 autofix(es) in 1 file(s); 1 issue(s) remain (conservative mode)."),
				log.messages.toString());
		assertEquals(
				List.of("- [WARNING] src/main/java/com/example/Dead.java:3:13 :: Class Dead is never referenced",
						"Summary: Applied 1 autofix(es) in 1 file(s); 1 issue(s) remain (conservative mode)."),
				log.warnings);
	}

	@Test
	void fixMojoSkipsWhenAsked() throws Exception {
		var mojo = fixMojo();
		setPrivateField(mojo, "skip", true);
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals(List.of("prune-java fix skipped."), log.messages);
		assertFalse(Files.exists(tempDir.resolve("src")));
	}

	@Test
	void fixMojoSkipsAggregatorModulesAndRewritesNothing() throws Exception {
		var service = write("core/src/main/java/com/example/Service.java",
				"package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		var mojo = fixMojo();
		setPrivateField(mojo, "packaging", "pom");
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertEquals(List.of("prune-java fix skipped for pom packaging; the modules are fixed individually."),
				log.messages);
		assertTrue(Files.readString(service).contains("private int orphan"));
	}

	@Test
	void checkMojoReportsTestOnlyReferencesWhenTestReferencesAreDisabled() throws Exception {
		write("src/main/java/com/example/Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
		write("src/test/java/com/example/HelperTest.java",
				"package com.example;\n\nclass HelperTest {\n    Helper h = new Helper();\n}\n");
		var included = checkMojo(true, "terminal");
		included.setLog(new CapturingLog());
		var excluded = checkMojo(true, "terminal");
		setPrivateField(excluded, "testReferences", false);
		var log = new CapturingLog();
		excluded.setLog(log);

		included.execute();
		var failure = assertThrows(MojoFailureException.class, excluded::execute);

		assertEquals("prune-java found 1 unused-code issue(s).", failure.getMessage());
		assertEquals(
				"- [WARNING] src/main/java/com/example/Helper.java:3:13 :: Class Helper is only referenced from tests",
				log.warnings.get(0));
	}

	@Test
	void checkMojoExplainsKeptSymbolsWhenAsked() throws Exception {
		write("src/main/java/com/example/Launcher.java",
				"package com.example;\n\nfinal class Launcher {\n    public static void main(String[] a) { }\n}\n");
		var mojo = checkMojo(true, "terminal");
		setPrivateField(mojo, "explain", true);
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertTrue(
				log.messages.contains("+ [KEPT] src/main/java/com/example/Launcher.java:3:13 :: "
						+ "Class Launcher is kept: it declares a main method a launcher can start [entry-point]"),
				log.messages.toString());
	}

	@Test
	void checkMojoAppliesTheDefaultBaselineAndAnExplicitOne() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		write("prune-baseline.txt", "UNUSED_CLASS com.example.Dead\n");
		write("config/accepted.txt", "UNUSED_FIELD com.example.Dead#orphan\n");
		var byDefault = checkMojo(true, "terminal");
		var defaultLog = new CapturingLog();
		byDefault.setLog(defaultLog);
		var explicit = checkMojo(false, "terminal");
		setPrivateField(explicit, "baseline", tempDir.resolve("config/accepted.txt").toFile());
		var explicitLog = new CapturingLog();
		explicit.setLog(explicitLog);

		byDefault.execute();
		explicit.execute();

		assertTrue(
				defaultLog.messages.contains("prune-java check: Analyzed 1 Java file(s) under " + tempDir
						+ ": 0 issue(s) found (conservative mode). 1 issue(s) suppressed by prune-baseline.txt."),
				defaultLog.messages.toString());
		// The explicit file replaces the default one, so Dead is reported again; its
		// entry matches nothing.
		assertEquals(List.of("- [WARNING] src/main/java/com/example/Dead.java:3:13 :: Class Dead is never referenced",
				"Summary: Analyzed 1 Java file(s) under " + tempDir + ": 1 issue(s) found (conservative mode)."),
				explicitLog.warnings);
	}

	@Test
	void checkMojoTurnsAMalformedBaselineIntoAnExecutionError() throws Exception {
		write("src/main/java/com/example/Live.java", "package com.example;\n\npublic class Live {\n}\n");
		write("prune-baseline.txt", "UNUSED_CLASS\n");
		var mojo = checkMojo(true, "terminal");
		mojo.setLog(new CapturingLog());

		var failure = assertThrows(MojoExecutionException.class, mojo::execute);

		assertEquals("prune-java could not analyze " + tempDir + ": " + tempDir.resolve("prune-baseline.txt")
				+ ":1: expected \"TYPE symbol\" but found \"UNUSED_CLASS\"", failure.getMessage());
	}

	@Test
	void fixMojoNeverTouchesABaselinedFinding() throws Exception {
		var service = write("src/main/java/com/example/Service.java",
				"package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		write("prune-baseline.txt", "UNUSED_FIELD com.example.Service#orphan\n");
		var mojo = fixMojo();
		var log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		assertTrue(Files.readString(service).contains("private int orphan"));
		assertTrue(
				log.messages.contains(
						"prune-java fix: Applied 0 autofix(es) in 0 file(s); 0 issue(s) remain (conservative mode)."),
				log.messages.toString());
	}

	@Test
	void baselineMojoWritesEveryFindingIgnoringTheExistingBaselineAndCheckThenPasses() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		write("prune-baseline.txt", "UNUSED_CLASS com.example.Dead\n");
		var baseline = new PruneBaselineMojo();
		setPrivateField(baseline, "projectDir", tempDir.toFile());
		setPrivateField(baseline, "testReferences", true);
		var baselineLog = new CapturingLog();
		baseline.setLog(baselineLog);
		var check = checkMojo(true, "terminal");
		var checkLog = new CapturingLog();
		check.setLog(checkLog);

		baseline.execute();
		check.execute();

		assertEquals(List.of("prune-java baseline: wrote 1 finding(s) to " + tempDir.resolve("prune-baseline.txt")),
				baselineLog.messages);
		assertEquals("""
				# prune-java baseline: findings this project accepts, one "TYPE symbol" per line.
				# check reports only findings that are not listed here; fix never touches a listed symbol.
				# Regenerate with the baseline command, goal, or task after reviewing the remaining findings.
				UNUSED_CLASS com.example.Dead
				""", Files.readString(tempDir.resolve("prune-baseline.txt")));
		assertEquals(List.of(), checkLog.warnings);
	}

	@Test
	void baselineMojoHonoursAnExplicitFileAndSkipsAggregatorsAndSkipRequests() throws Exception {
		write("src/main/java/com/example/Dead.java", DEAD);
		var explicit = new PruneBaselineMojo();
		setPrivateField(explicit, "projectDir", tempDir.toFile());
		setPrivateField(explicit, "testReferences", true);
		setPrivateField(explicit, "baseline", tempDir.resolve("config/accepted.txt").toFile());
		explicit.setLog(new CapturingLog());
		var aggregator = new PruneBaselineMojo();
		setPrivateField(aggregator, "projectDir", tempDir.toFile());
		setPrivateField(aggregator, "packaging", "pom");
		var aggregatorLog = new CapturingLog();
		aggregator.setLog(aggregatorLog);
		var skipped = new PruneBaselineMojo();
		setPrivateField(skipped, "projectDir", tempDir.toFile());
		setPrivateField(skipped, "skip", true);
		var skippedLog = new CapturingLog();
		skipped.setLog(skippedLog);

		Files.createDirectories(tempDir.resolve("config"));
		explicit.execute();
		aggregator.execute();
		skipped.execute();

		assertTrue(
				Files.readString(tempDir.resolve("config/accepted.txt")).endsWith("UNUSED_CLASS com.example.Dead\n"));
		assertFalse(Files.exists(tempDir.resolve("prune-baseline.txt")));
		assertEquals(List.of("prune-java baseline skipped for pom packaging; the modules are baselined individually."),
				aggregatorLog.messages);
		assertEquals(List.of("prune-java baseline skipped."), skippedLog.messages);
	}

	@Test
	void baselineMojoTurnsAnUnwritableFileIntoAnExecutionError() throws Exception {
		Files.createDirectory(tempDir.resolve("prune-baseline.txt"));
		var mojo = new PruneBaselineMojo();
		setPrivateField(mojo, "projectDir", tempDir.toFile());
		setPrivateField(mojo, "testReferences", true);
		mojo.setLog(new CapturingLog());

		var failure = assertThrows(MojoExecutionException.class, mojo::execute);

		assertTrue(failure.getMessage().startsWith("prune-java could not write the baseline for " + tempDir),
				failure.getMessage());
	}

	private PruneCheckMojo checkMojo(boolean failOnIssues, String format) throws Exception {
		var mojo = new PruneCheckMojo();
		setPrivateField(mojo, "projectDir", tempDir.toFile());
		setPrivateField(mojo, "failOnIssues", failOnIssues);
		setPrivateField(mojo, "format", format);
		// Maven injects the declared default; a hand-built mojo starts from Java's false.
		setPrivateField(mojo, "testReferences", true);
		return mojo;
	}

	private PruneFixMojo fixMojo() throws Exception {
		var mojo = new PruneFixMojo();
		setPrivateField(mojo, "projectDir", tempDir.toFile());
		setPrivateField(mojo, "format", "terminal");
		setPrivateField(mojo, "testReferences", true);
		return mojo;
	}

	private Path write(String relativePath, String content) throws IOException {
		var file = tempDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class CapturingLog implements Log {

		private final List<String> messages = new ArrayList<>();

		private final List<String> warnings = new ArrayList<>();

		@Override
		public boolean isDebugEnabled() {
			return true;
		}

		@Override
		public void debug(CharSequence content) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void debug(CharSequence content, Throwable error) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void debug(Throwable error) {
			messages.add(String.valueOf(error.getMessage()));
		}

		@Override
		public boolean isInfoEnabled() {
			return true;
		}

		@Override
		public void info(CharSequence content) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void info(CharSequence content, Throwable error) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void info(Throwable error) {
			messages.add(String.valueOf(error.getMessage()));
		}

		@Override
		public boolean isWarnEnabled() {
			return true;
		}

		@Override
		public void warn(CharSequence content) {
			warnings.add(String.valueOf(content));
		}

		@Override
		public void warn(CharSequence content, Throwable error) {
			warnings.add(String.valueOf(content));
		}

		@Override
		public void warn(Throwable error) {
			warnings.add(String.valueOf(error.getMessage()));
		}

		@Override
		public boolean isErrorEnabled() {
			return true;
		}

		@Override
		public void error(CharSequence content) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void error(CharSequence content, Throwable error) {
			messages.add(String.valueOf(content));
		}

		@Override
		public void error(Throwable error) {
			messages.add(String.valueOf(error.getMessage()));
		}

	}

}
