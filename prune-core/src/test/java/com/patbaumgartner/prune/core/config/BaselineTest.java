package com.patbaumgartner.prune.core.config;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineTest {

	@TempDir
	Path dir;

	@Test
	void matchesByTypeAndSymbolAndIgnoresLocationAndMessage() {
		var baseline = Baseline.parse("""
				# accepted
				UNUSED_METHOD com.example.App#helper

				UNUSED_DEPENDENCY com.google.guava:guava
				""", "prune-baseline.txt");

		assertEquals(2, baseline.size());
		assertTrue(baseline.contains(
				issue(IssueType.UNUSED_METHOD, "com.example.App#helper", "src/main/java/com/example/App.java:9:18")));
		assertTrue(baseline.contains(issue(IssueType.UNUSED_METHOD, "com.example.App#helper", "elsewhere/App.java:1")));
		assertTrue(baseline.contains(issue(IssueType.UNUSED_DEPENDENCY, "com.google.guava:guava", "pom.xml:20")));
		assertFalse(baseline.contains(
				issue(IssueType.UNUSED_FIELD, "com.example.App#helper", "src/main/java/com/example/App.java:9:18")));
		assertFalse(baseline.contains(
				issue(IssueType.UNUSED_METHOD, "com.example.App#other", "src/main/java/com/example/App.java:9:18")));
	}

	@Test
	void rejectsMalformedLinesAndUnknownTypesNamingTheSourceAndLine() {
		var malformed = assertThrows(IllegalArgumentException.class,
				() -> Baseline.parse("UNUSED_METHOD\n", "accepted.txt"));
		var unknown = assertThrows(IllegalArgumentException.class,
				() -> Baseline.parse("# header\nUNUSED_THING com.example.A\n", "accepted.txt"));

		assertEquals("accepted.txt:1: expected \"TYPE symbol\" but found \"UNUSED_METHOD\"", malformed.getMessage());
		assertEquals("accepted.txt:2: unknown issue type UNUSED_THING", unknown.getMessage());
	}

	@Test
	void rendersSortedUniqueEntriesUnderAHeaderThatParsesBackToTheSameBaseline() {
		var issues = List.of(
				issue(IssueType.UNUSED_METHOD, "com.example.App#helper", "src/main/java/com/example/App.java:9:18"),
				issue(IssueType.UNUSED_CLASS, "com.example.Dead", "src/main/java/com/example/Dead.java:3:13"),
				issue(IssueType.UNUSED_METHOD, "com.example.App#helper", "src/main/java/com/example/App.java:9:18"));

		var rendered = Baseline.render(issues);

		assertEquals("""
				# prune-java baseline: findings this project accepts, one "TYPE symbol" per line.
				# check reports only findings that are not listed here; fix never touches a listed symbol.
				# Regenerate with the baseline command, goal, or task after reviewing the remaining findings.
				UNUSED_CLASS com.example.Dead
				UNUSED_METHOD com.example.App#helper
				""", rendered);
		var parsed = Baseline.parse(rendered, "rendered");
		assertEquals(2, parsed.size());
		assertTrue(parsed.contains(issues.get(0)));
		assertTrue(parsed.contains(issues.get(1)));
	}

	@Test
	void aMissingFileLoadsAsAnEmptyBaseline() throws IOException {
		assertEquals(0, Baseline.load(dir.resolve("prune-baseline.txt")).size());
		assertEquals(0, Baseline.empty().size());

		Files.writeString(dir.resolve("prune-baseline.txt"), "UNUSED_CLASS com.example.Dead\n", StandardCharsets.UTF_8);

		assertEquals(1, Baseline.load(dir.resolve("prune-baseline.txt")).size());
	}

	private static AnalysisIssue issue(IssueType type, String symbol, String location) {
		return new AnalysisIssue(type, Severity.WARNING, symbol, location, "message", false);
	}

}
