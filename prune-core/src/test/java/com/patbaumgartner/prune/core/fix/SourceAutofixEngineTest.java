package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceAutofixEngineTest {

	private static final String MAIN = "src/main/java/com/example/";

	@TempDir
	Path root;

	@Test
	void removesUnusedPrivateMembersTogetherWithAttachedCommentsAndSurroundingBlankLine() throws Exception {
		var file = write(MAIN + "Service.java", """
				package com.example;

				public class Service {

				    // Legacy suffix, kept for old reports.
				    private static final String LEGACY = " (legacy)";

				    public String format() {
				        return "x";
				    }

				    /**
				     * Rounds.
				     */
				    private long round(long cents) {
				        return cents;
				    }
				}
				""");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals("Applied 2 autofix(es) in 1 file(s); 0 issue(s) remain (conservative mode).", remaining.summary());
		assertEquals("""
				package com.example;

				public class Service {

				    public String format() {
				        return "x";
				    }
				}
				""", Files.readString(file));
	}

	@Test
	void removesOnlyTheMemberWhenItSharesALineAndKeepsMultiDeclaratorFields() throws Exception {
		var file = write(MAIN + "Pair.java",
				"package com.example;\n\npublic class Pair {\n    private int a, b; private int c; int used() { return 1; }\n}\n");

		var remaining = fix();

		assertEquals(List.of("com.example.Pair#a", "com.example.Pair#b"),
				remaining.issues().stream().map(AnalysisIssue::symbol).toList());
		assertEquals("package com.example;\n\npublic class Pair {\n    private int a, b; int used() { return 1; }\n}\n",
				Files.readString(file));
	}

	@Test
	void reducesVisibilityByDroppingThePublicModifier() throws Exception {
		var codes = write(MAIN + "Codes.java",
				"package com.example;\n\npublic final class Codes {\n    public static final String DEFAULT = \"CHF\";\n    private Codes() { }\n}\n");
		write(MAIN + "User.java",
				"package com.example;\n\npublic class User {\n    String code() { return Codes.DEFAULT; }\n}\n");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals(
				"package com.example;\n\nfinal class Codes {\n    public static final String DEFAULT = \"CHF\";\n    private Codes() { }\n}\n",
				Files.readString(codes));
	}

	@Test
	void removesUnusedMavenDependenciesWithTheirLeadingCommentAndBlankLine() throws Exception {
		var pom = write("pom.xml", """
				<project>
				    <modelVersion>4.0.0</modelVersion>
				    <groupId>com.example</groupId>
				    <artifactId>demo</artifactId>
				    <version>1</version>
				    <dependencies>
				        <dependency>
				            <groupId>com.google.code.gson</groupId>
				            <artifactId>gson</artifactId>
				            <version>2.11.0</version>
				        </dependency>

				        <!-- Never imported. -->
				        <dependency>
				            <groupId>org.apache.commons</groupId>
				            <artifactId>commons-lang3</artifactId>
				            <version>3.17.0</version>
				        </dependency>
				    </dependencies>
				</project>
				""");
		write(MAIN + "App.java",
				"package com.example;\n\nimport com.google.gson.Gson;\n\npublic class App {\n    Object g = new Gson();\n}\n");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals("""
				<project>
				    <modelVersion>4.0.0</modelVersion>
				    <groupId>com.example</groupId>
				    <artifactId>demo</artifactId>
				    <version>1</version>
				    <dependencies>
				        <dependency>
				            <groupId>com.google.code.gson</groupId>
				            <artifactId>gson</artifactId>
				            <version>2.11.0</version>
				        </dependency>
				    </dependencies>
				</project>
				""", Files.readString(pom));
	}

	@Test
	void removesUnusedGradleDependencyLines() throws Exception {
		var build = write("build.gradle",
				"plugins { id 'java' }\n\ndependencies {\n    implementation 'org.slf4j:slf4j-api:2.0.16'\n"
						+ "    // unused\n    implementation 'commons-io:commons-io:2.17.0'\n    implementation('com.google.guava:guava:33.0.0-jre') {\n"
						+ "        exclude group: 'x'\n    }\n}\n");
		write(MAIN + "App.java",
				"package com.example;\n\nimport org.slf4j.Logger;\n\npublic class App {\n    Logger log;\n}\n");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals("plugins { id 'java' }\n\ndependencies {\n    implementation 'org.slf4j:slf4j-api:2.0.16'\n}\n",
				Files.readString(build));
	}

	@Test
	void leavesUnusedClassesForAHumanAndPlansNothingForThem() throws Exception {
		var dead = write(MAIN + "Dead.java", "package com.example;\n\nfinal class Dead {\n}\n");

		var remaining = fix();

		assertEquals(List.of("com.example.Dead"), remaining.issues().stream().map(AnalysisIssue::symbol).toList());
		assertEquals("Applied 0 autofix(es) in 0 file(s); 1 issue(s) remain (conservative mode).", remaining.summary());
		assertTrue(Files.exists(dead));
	}

	@Test
	void removesImportsThatOnlyTheRemovedMemberNeededAndKeepsTheRest() throws Exception {
		var file = write(MAIN + "Service.java", """
				package com.example;

				import java.util.Arrays;
				import java.util.Collection;
				import java.util.List;
				import java.util.regex.Pattern;
				import static java.util.Objects.requireNonNull;
				import static java.util.Objects.hash;

				/**
				 * Uses {@link Pattern} only in documentation.
				 */
				public class Service {

				    private static final Collection<String> NAMES = Arrays.asList("scope", "scp");

				    private static int unusedHash(Object o) {
				        return hash(o);
				    }

				    public List<String> names(Object o) {
				        return List.of(requireNonNull(o).toString());
				    }
				}
				""");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals("""
				package com.example;

				import java.util.List;
				import java.util.regex.Pattern;
				import static java.util.Objects.requireNonNull;

				/**
				 * Uses {@link Pattern} only in documentation.
				 */
				public class Service {

				    public List<String> names(Object o) {
				        return List.of(requireNonNull(o).toString());
				    }
				}
				""", Files.readString(file));
	}

	@Test
	void leavesImportsThatWereAlreadyUnusedBeforeTheFixAlone() throws Exception {
		var file = write(MAIN + "Service.java",
				"package com.example;\n\nimport java.util.Map;\n\npublic class Service {\n    private int orphan;\n}\n");

		fix();

		assertEquals("package com.example;\n\nimport java.util.Map;\n\npublic class Service {\n}\n",
				Files.readString(file));
	}

	@Test
	void removesConsecutiveOrphanedImportsAsOneBlockWithoutLeavingDoubledBlankLines() throws Exception {
		var file = write(MAIN + "Service.java", """
				package com.example;

				import java.util.ArrayDeque;
				import java.util.Deque;

				public class Service {
				    private Deque<String> orphan = new ArrayDeque<>();
				}
				""");

		var plan = new SourceAutofixEngine(root).plan(analyze());
		new SourceAutofixEngine(root).apply(plan);

		assertEquals(
				List.of("Remove import java.util.ArrayDeque, java.util.Deque left unused",
						"Remove unused private field orphan"),
				plan.actions().stream().map(FixAction::description).toList());
		assertEquals("package com.example;\n\npublic class Service {\n}\n", Files.readString(file));
	}

	@Test
	void preservesCrlfLineEndingsWhenRemovingMembers() throws Exception {
		var file = write(MAIN + "Service.java",
				"package com.example;\r\n\r\npublic class Service {\r\n\r\n    private int orphan;\r\n\r\n    public int live() {\r\n        return 1;\r\n    }\r\n}\r\n");

		var remaining = fix();

		assertEquals(List.of(), remaining.issues());
		assertEquals(
				"package com.example;\r\n\r\npublic class Service {\r\n\r\n    public int live() {\r\n        return 1;\r\n    }\r\n}\r\n",
				Files.readString(file));
	}

	@Test
	void removesAMultiLineXmlCommentAttachedToAnUnusedDependency() throws Exception {
		var pom = write("pom.xml", """
				<project>
				    <modelVersion>4.0.0</modelVersion>
				    <groupId>com.example</groupId>
				    <artifactId>demo</artifactId>
				    <version>1</version>
				    <dependencies>
				        <!--
				          Kept for an experiment that never shipped.
				          Nobody imports it.
				        -->
				        <dependency>
				            <groupId>org.apache.commons</groupId>
				            <artifactId>commons-lang3</artifactId>
				            <version>3.17.0</version>
				        </dependency>
				    </dependencies>
				</project>
				""");
		write(MAIN + "App.java", "package com.example;\n\npublic class App {\n}\n");

		fix();

		assertEquals("""
				<project>
				    <modelVersion>4.0.0</modelVersion>
				    <groupId>com.example</groupId>
				    <artifactId>demo</artifactId>
				    <version>1</version>
				    <dependencies>
				    </dependencies>
				</project>
				""", Files.readString(pom));
	}

	@Test
	void importCleanupIsPartOfTheSameIssueSoASingleIssuePlanCarriesBothEdits() throws Exception {
		write(MAIN + "Service.java",
				"package com.example;\n\nimport java.util.Set;\n\npublic class Service {\n    private Set<String> orphan;\n}\n");

		var plan = new SourceAutofixEngine(root).plan(analyze());

		assertEquals(List.of("Remove import java.util.Set left unused", "Remove unused private field orphan"),
				plan.actions().stream().map(FixAction::description).toList());
		assertEquals(1, plan.fixedIssues().size());
	}

	@Test
	void rewritesKeepTheFilePermissionsAndLeaveNoTemporaryFileBehind() throws Exception {
		var file = write(MAIN + "Service.java",
				"package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		var permissions = PosixFilePermissions.fromString("rwxr-x---");
		Files.setPosixFilePermissions(file, permissions);

		fix();

		assertEquals(permissions, Files.getPosixFilePermissions(file));
		assertEquals("package com.example;\n\npublic class Service {\n}\n", Files.readString(file));
		try (var siblings = Files.list(file.getParent())) {
			assertEquals(List.of("Service.java"), siblings.map(path -> path.getFileName().toString()).toList());
		}
	}

	@Test
	void neverRewritesThroughASymlinkThatLeavesTheProject() throws Exception {
		var outside = Files.createDirectories(root.resolve("../outside-" + root.getFileName()));
		try {
			var target = Files.writeString(outside.resolve("Leak.java"),
					"package com.example;\n\npublic class Leak {\n    private int orphan;\n}\n");
			var link = root.resolve(MAIN + "Leak.java");
			Files.createDirectories(link.getParent());
			try {
				Files.createSymbolicLink(link, target);
			}
			catch (UnsupportedOperationException | IOException cannotLink) {
				assumeTrue(false, "symbolic links are not available here");
			}

			var remaining = fix();

			assertEquals(List.of("com.example.Leak#orphan"),
					remaining.issues().stream().map(AnalysisIssue::symbol).toList());
			assertTrue(Files.readString(target).contains("private int orphan"));
		}
		finally {
			Files.deleteIfExists(outside.resolve("Leak.java"));
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void refusesToApplyAPlanWhenTheFileChangedSincePlanning() throws Exception {
		var file = write(MAIN + "Service.java",
				"package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		var engine = new SourceAutofixEngine(root);
		var plan = engine.plan(analyze());
		Files.writeString(file,
				"package com.example;\n\npublic class Service {\n    int moved;\n    private int orphan;\n}\n");

		assertThrows(IllegalStateException.class, () -> engine.apply(plan));
		assertEquals("package com.example;\n\npublic class Service {\n    int moved;\n    private int orphan;\n}\n",
				Files.readString(file));
	}

	@Test
	void refusesToApplyAPlanWhenTheFileBecameASymlinkLeavingTheProject() throws Exception {
		var source = "package com.example;\n\npublic class Service {\n    private int orphan;\n}\n";
		var file = write(MAIN + "Service.java", source);
		var outside = Files.createDirectories(root.resolve("../outside-" + root.getFileName()));
		try {
			var target = Files.writeString(outside.resolve("Service.java"), source);
			var engine = new SourceAutofixEngine(root);
			var plan = engine.plan(analyze());
			Files.delete(file);
			try {
				Files.createSymbolicLink(file, target);
			}
			catch (UnsupportedOperationException | IOException cannotLink) {
				assumeTrue(false, "symbolic links are not available here");
			}

			assertThrows(IllegalStateException.class, () -> engine.apply(plan));
			assertEquals(source, Files.readString(target));
		}
		finally {
			Files.deleteIfExists(outside.resolve("Service.java"));
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void ignoresIssuesThatPointOutsideTheProjectOrAtTheWrongLine() throws Exception {
		write(MAIN + "Service.java", "package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");
		var outside = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "x.Y#z", "../../etc/passwd:1:1", "m",
				true);
		var wrongLine = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "com.example.Service#orphan",
				"src/main/java/com/example/Service.java:2:17", "m", true);
		var unfixable = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "com.example.Service#orphan",
				"src/main/java/com/example/Service.java:4:17", "m", false);

		var plan = new SourceAutofixEngine(root)
			.plan(new AnalysisReport(List.of(outside, wrongLine, unfixable), "s", true));

		assertTrue(plan.isEmpty());
	}

	@Test
	void planDescribesEachActionAndCarriesTheOriginalText() throws Exception {
		write(MAIN + "Service.java", "package com.example;\n\npublic class Service {\n    private int orphan;\n}\n");

		var plan = new SourceAutofixEngine(root).plan(analyze());

		assertEquals(1, plan.actions().size());
		var action = plan.actions().get(0);
		assertEquals("Remove unused private field orphan", action.description());
		assertEquals("    private int orphan;\n", action.original());
		assertEquals("", action.replacement());
		assertEquals(root.resolve(MAIN + "Service.java").toAbsolutePath().normalize(), action.file());
	}

	@Test
	void fixActionRejectsAnOriginalThatDoesNotCoverItsSpan() {
		var issue = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "A#b", "A.java:1", "m", true);

		assertThrows(IllegalArgumentException.class,
				() -> new FixAction(issue, Path.of("A.java"), 0, 5, "abc", "", "d"));
		assertThrows(IllegalArgumentException.class, () -> new FixAction(issue, Path.of("A.java"), 5, 2, "", "", "d"));
	}

	private AnalysisReport fix() {
		return new SourceAutofixEngine(root).fix(analyze());
	}

	private AnalysisReport analyze() {
		return new ConservativeUnusedCodeAnalyzer().analyze(AnalysisConfig.defaultFor(root));
	}

	private Path write(String relativePath, String content) throws IOException {
		var file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

}
