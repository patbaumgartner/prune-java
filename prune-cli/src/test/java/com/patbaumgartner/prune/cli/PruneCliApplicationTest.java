package com.patbaumgartner.prune.cli;

import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.FixAction;
import com.patbaumgartner.prune.core.fix.FixPlan;
import com.patbaumgartner.prune.core.fix.FixResult;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneCliApplicationTest {

	private static final AnalysisIssue FIXABLE = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "a.B#m",
			"src/main/java/a/B.java:3:5", "Private method m is never used", true);

	private static final AnalysisIssue MANUAL = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.C",
			"src/main/java/a/C.java:1:7", "Class C is never referenced", false);

	@TempDir
	Path tempDir;

	@Test
	void checkCommandReturnsSuccessWithNoIssues() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--format=terminal" }, printer(stdout), printer(stderr));

		assertEquals(0, exitCode);
		assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("No unused code detected"));
		assertEquals("", stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void checkCommandReturnsOneAndRendersIssuesWhenIssuesAreReported() {
		var app = new PruneCliApplication(config -> new AnalysisReport(List.of(MANUAL), "1 issue", true),
				new ReportRenderer());
		var stdout = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--format=terminal" }, printer(stdout), discard());

		assertEquals(1, exitCode);
		assertEquals("- [WARNING] src/main/java/a/C.java:1:7 :: Class C is never referenced" + System.lineSeparator()
				+ "Summary: 1 issue" + System.lineSeparator(), stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void checkCommandAnalyzesTheWorkingDirectoryByDefaultAndTheRootOptionOtherwise() throws IOException {
		var roots = new ArrayList<Path>();
		UnusedCodeAnalyzer analyzer = config -> {
			roots.add(config.projectRoot());
			return emptyReport();
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());
		var other = Files.createDirectory(tempDir.resolve("other"));

		app.run(new String[] { "check" }, discard(), discard());
		app.run(new String[] { "check", "--root=" + other }, discard(), discard());

		assertEquals(List.of(Path.of("."), other), roots);
	}

	@Test
	void rootOptionMustNameAnExistingDirectory() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--root=" + tempDir.resolve("missing") }, discard(),
				printer(stderr));

		assertEquals(2, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("Project root is not a directory: "));
	}

	@Test
	void excludeOptionIsRepeatableAndReachesTheAnalyzerAsExcludePatterns() {
		var excludes = new ArrayList<List<String>>();
		UnusedCodeAnalyzer analyzer = config -> {
			excludes.add(config.excludePatterns());
			return emptyReport();
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());

		app.run(new String[] { "check", "--exclude=**/generated/**", "--exclude=**/*Stub.java" }, discard(), discard());
		app.run(new String[] { "check" }, discard(), discard());

		assertEquals(List.of(List.of("**/generated/**", "**/*Stub.java"), List.of()), excludes);
	}

	@Test
	void excludeOptionRejectsAnEmptyPattern() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--exclude=" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertEquals("Option --exclude needs a glob pattern" + System.lineSeparator(),
				stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void excludeOptionRejectsAnUnclosedBraceGroupAsAUsageError() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--exclude=**/{Foo" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertEquals("Invalid glob pattern '**/{Foo': missing '}'" + System.lineSeparator(),
				stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void fixCommandAppliesThePlanAndReportsOnlyWhatRemains() {
		var engine = new RecordingEngine();
		var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE, MANUAL), "2 issues", true),
				new ReportRenderer(), root -> engine);
		var stdout = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "fix", "--root=" + tempDir, "--format=json" }, printer(stdout),
				discard());

		assertEquals(1, exitCode);
		assertEquals(List.of(FIXABLE), engine.plannedFor.get(0).issues());
		assertTrue(engine.applied);
		assertEquals("{\"summary\":\"Applied 1 autofix(es) in 1 file(s); 1 issue(s) remain (conservative mode).\","
				+ "\"conservativeMode\":true,\"issues\":[{\"type\":\"UNUSED_CLASS\",\"severity\":\"WARNING\",\"symbol\":\"a.C\","
				+ "\"location\":\"src/main/java/a/C.java:1:7\",\"message\":\"Class C is never referenced\",\"autoFixable\":false}],\"kept\":[]}"
				+ System.lineSeparator(), stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void fixCommandReturnsZeroWhenNothingRemains() {
		var engine = new RecordingEngine();
		var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE), "1 issue", true),
				new ReportRenderer(), root -> engine);
		var stdout = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "fix", "--root=" + tempDir }, printer(stdout), discard());

		assertEquals(0, exitCode);
		assertEquals("No unused code detected (conservative mode)." + System.lineSeparator()
				+ "Summary: Applied 1 autofix(es) in 1 file(s); 0 issue(s) remain (conservative mode)."
				+ System.lineSeparator(), stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void fixCommandPassesTheAnalyzedRootToTheEngine() {
		var roots = new ArrayList<Path>();
		UnusedCodeAnalyzer analyzer = config -> emptyReport();
		var app = new PruneCliApplication(analyzer, new ReportRenderer(), root -> {
			roots.add(root);
			return new RecordingEngine();
		});

		app.run(new String[] { "fix", "--root=" + tempDir }, discard(), discard());

		assertEquals(List.of(tempDir), roots);
	}

	@Test
	void analysisFailuresExitWithThreeAndExplainOnStderr() {
		UnusedCodeAnalyzer analyzer = config -> {
			throw new UncheckedIOException(new IOException("disk on fire"));
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());
		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check" }, printer(stdout), printer(stderr));

		assertEquals(3, exitCode);
		assertEquals("", stdout.toString(StandardCharsets.UTF_8));
		assertTrue(stderr.toString(StandardCharsets.UTF_8)
			.contains("prune-java failed: java.io.IOException: disk on fire"));
	}

	@Test
	void staleFixPlansExitWithThreeWithoutRenderingAReport() {
		var app = new PruneCliApplication(config -> new AnalysisReport(List.of(FIXABLE), "1", true),
				new ReportRenderer(), root -> new RecordingEngine() {
					@Override
					public FixResult apply(FixPlan plan) {
						throw new IllegalStateException("B.java changed since the fix was planned");
					}
				});
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "fix", "--root=" + tempDir }, discard(), printer(stderr));

		assertEquals(3, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("changed since the fix was planned"));
	}

	@Test
	void invalidFormatReturnsError() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--format=invalid" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unsupported format: invalid"));
	}

	@Test
	void unknownCommandReturnsUsageError() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "purge" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unknown command: purge"));
	}

	@Test
	void unknownOptionReturnsUsageError() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--formta=json" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unknown option: --formta=json"));
	}

	@Test
	void helpPrintsUsageIncludingTheRootOption() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stdout = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "--help" }, printer(stdout), discard());

		assertEquals(0, exitCode);
		assertEquals("Usage: prune-java <check|fix|baseline> [--root=<dir>] [--exclude=<glob>]... [--baseline=<file>]"
				+ " [--no-test-references] [--explain] [--ci] [--format=terminal|github|json]" + System.lineSeparator(),
				stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void explainAndTestReferenceOptionsReachTheAnalyzerAndDefaultToOffAndOn() {
		var explains = new ArrayList<Boolean>();
		var testReferences = new ArrayList<Boolean>();
		UnusedCodeAnalyzer analyzer = config -> {
			explains.add(config.explain());
			testReferences.add(config.includeTestReferences());
			return emptyReport();
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());

		app.run(new String[] { "check" }, discard(), discard());
		app.run(new String[] { "check", "--explain", "--no-test-references" }, discard(), discard());

		assertEquals(List.of(false, true), explains);
		assertEquals(List.of(true, false), testReferences);
	}

	@Test
	void baselineOptionOverridesTheDefaultBaselineFileAndRejectsAnEmptyValue() {
		var baselines = new ArrayList<Path>();
		UnusedCodeAnalyzer analyzer = config -> {
			baselines.add(config.baseline().orElseThrow());
			return emptyReport();
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		app.run(new String[] { "check", "--root=" + tempDir }, discard(), discard());
		app.run(new String[] { "check", "--root=" + tempDir, "--baseline=" + tempDir.resolve("accepted.txt") },
				discard(), discard());
		int exitCode = app.run(new String[] { "check", "--baseline=" }, discard(), printer(stderr));

		assertEquals(List.of(tempDir.resolve("prune-baseline.txt"), tempDir.resolve("accepted.txt")), baselines);
		assertEquals(2, exitCode);
		assertEquals("Option --baseline needs a file path" + System.lineSeparator(),
				stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void baselineCommandAnalyzesWithoutTheExistingBaselineAndWritesEveryFinding() throws IOException {
		var baselines = new ArrayList<Optional<Path>>();
		UnusedCodeAnalyzer analyzer = config -> {
			baselines.add(config.baseline());
			return new AnalysisReport(List.of(FIXABLE, MANUAL), "2 issues", true);
		};
		var app = new PruneCliApplication(analyzer, new ReportRenderer());
		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "baseline", "--root=" + tempDir }, printer(stdout), printer(stderr));

		assertEquals(0, exitCode);
		assertEquals(List.of(Optional.empty()), baselines);
		assertEquals("Wrote 2 finding(s) to " + tempDir.resolve("prune-baseline.txt") + System.lineSeparator(),
				stdout.toString(StandardCharsets.UTF_8));
		assertEquals("", stderr.toString(StandardCharsets.UTF_8));
		assertEquals("""
				# prune-java baseline: findings this project accepts, one "TYPE symbol" per line.
				# check reports only findings that are not listed here; fix never touches a listed symbol.
				# Regenerate with the baseline command, goal, or task after reviewing the remaining findings.
				UNUSED_CLASS a.C
				UNUSED_METHOD a.B#m
				""", Files.readString(tempDir.resolve("prune-baseline.txt"), StandardCharsets.UTF_8));
	}

	@Test
	void baselineCommandThenCheckSuppressesEveryRecordedFindingWithTheRealAnalyzer() throws IOException {
		var source = Files.createDirectories(tempDir.resolve("src/main/java/com/example")).resolve("App.java");
		Files.writeString(source, "package com.example;\n\npublic class App {\n    private void helper() { }\n}\n",
				StandardCharsets.UTF_8);
		var app = new PruneCliApplication();
		var stdout = new ByteArrayOutputStream();

		int before = app.run(new String[] { "check", "--root=" + tempDir }, discard(), discard());
		int baseline = app.run(new String[] { "baseline", "--root=" + tempDir }, discard(), discard());
		int after = app.run(new String[] { "check", "--root=" + tempDir, "--explain" }, printer(stdout), discard());

		assertEquals(List.of(1, 0, 0), List.of(before, baseline, after));
		assertEquals("+ [KEPT] src/main/java/com/example/App.java:4:18 :: accepted in prune-baseline.txt [baseline]"
				+ System.lineSeparator() + "No unused code detected (conservative mode)." + System.lineSeparator()
				+ "Summary: Analyzed 1 Java file(s) under " + tempDir + ": 0 issue(s) found (conservative mode)."
				+ " 1 issue(s) suppressed by prune-baseline.txt." + System.lineSeparator(),
				stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void aMalformedBaselineFileFailsTheRunWithThree() throws IOException {
		Files.writeString(tempDir.resolve("prune-baseline.txt"), "nonsense\n", StandardCharsets.UTF_8);
		var app = new PruneCliApplication();
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--root=" + tempDir }, discard(), printer(stderr));

		assertEquals(3, exitCode);
		assertEquals(
				"prune-java failed: " + tempDir.resolve("prune-baseline.txt")
						+ ":1: expected \"TYPE symbol\" but found \"nonsense\"" + System.lineSeparator(),
				stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void baselineCommandFailsWithThreeWhenTheFileCannotBeWritten() throws IOException {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();
		Files.createDirectory(tempDir.resolve("prune-baseline.txt"));

		int exitCode = app.run(new String[] { "baseline", "--root=" + tempDir }, discard(), printer(stderr));

		assertEquals(3, exitCode);
		assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("prune-java failed: "),
				stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void ciFlagSelectsGithubAnnotationOutput() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stdout = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "check", "--ci" }, printer(stdout), discard());

		assertEquals(0, exitCode);
		assertTrue(stdout.toString(StandardCharsets.UTF_8).startsWith("::notice::"));
	}

	@Test
	void explicitFormatWinsOverCiDefault() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stdout = new ByteArrayOutputStream();

		app.run(new String[] { "check", "--ci", "--format=json" }, printer(stdout), discard());

		assertTrue(stdout.toString(StandardCharsets.UTF_8).startsWith("{\"summary\":"));
	}

	@Test
	void commandParsingIgnoresTheDefaultLocale() {
		var original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer(),
					root -> new RecordingEngine());
			var stderr = new ByteArrayOutputStream();

			int exitCode = app.run(new String[] { "FIX", "--root=" + tempDir }, discard(), printer(stderr));

			assertEquals(0, exitCode);
			assertEquals("", stderr.toString(StandardCharsets.UTF_8));
		}
		finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void commandParsingFoldsOnlyAsciiLetters() {
		var app = new PruneCliApplication(config -> emptyReport(), new ReportRenderer());
		var stderr = new ByteArrayOutputStream();

		int exitCode = app.run(new String[] { "chec\u212A" }, discard(), printer(stderr));

		assertEquals(2, exitCode);
		assertEquals("Unknown command: chec\u212A" + System.lineSeparator(), stderr.toString(StandardCharsets.UTF_8));
	}

	private static AnalysisReport emptyReport() {
		return new AnalysisReport(List.of(), "empty", true);
	}

	private static class RecordingEngine implements AutofixEngine {

		final List<AnalysisReport> plannedFor = new ArrayList<>();

		boolean applied;

		@Override
		public FixPlan plan(AnalysisReport report) {
			var fixable = report.issues().stream().filter(AnalysisIssue::autoFixable).toList();
			plannedFor.add(new AnalysisReport(fixable, report.summary(), true));
			return new FixPlan(fixable.stream()
				.map(issue -> new FixAction(issue, Path.of("B.java"), 0, 1, "x", "", "remove"))
				.toList());
		}

		@Override
		public FixResult apply(FixPlan plan) {
			applied = true;
			return new FixResult(plan.isEmpty() ? 0 : 1, plan.actions().size());
		}

	}

	private static PrintStream printer(OutputStream target) {
		return new PrintStream(target, true, StandardCharsets.UTF_8);
	}

	private static PrintStream discard() {
		return printer(OutputStream.nullOutputStream());
	}

}
