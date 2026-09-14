package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticMapperTest {

	private final DiagnosticMapper mapper = new DiagnosticMapper();

	@TempDir
	Path tempDir;

	@Test
	void emptyReportProducesNoDiagnostics() {
		var report = new AnalysisReport(List.of(), "nothing", true);

		var byUri = mapper.map(report, tempDir);

		assertTrue(byUri.isEmpty());
	}

	@Test
	void mapsIssueFieldsOntoDiagnostic() {
		var issue = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "Foo#bar", "src/main/java/Foo.java",
				"Method bar is unused", false);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var byUri = mapper.map(report, tempDir);

		var expectedUri = tempDir.resolve("src/main/java/Foo.java").toUri().toString();
		var diagnostics = byUri.get(expectedUri);
		assertEquals(1, diagnostics.size());
		var diagnostic = diagnostics.get(0);
		assertEquals("Method bar is unused", diagnostic.getMessage().getLeft());
		assertEquals(DiagnosticSeverity.Warning, diagnostic.getSeverity());
		assertEquals("prune-java", diagnostic.getSource());
		assertEquals("UNUSED_METHOD", diagnostic.getCode().getLeft());
		assertEquals(List.of(DiagnosticTag.Unnecessary), diagnostic.getTags());
		assertEquals(new Range(new Position(0, 0), new Position(0, 0)), diagnostic.getRange());
	}

	@Test
	void trailingLineAndColumnBecomeZeroBasedPositionAndTheRangeSpansTheMemberName() {
		var issue = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.INFO, "a.Foo#counter",
				"src/main/java/Foo.java:12:5", "unused", true);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var byUri = mapper.map(report, tempDir);

		var expectedUri = tempDir.resolve("src/main/java/Foo.java").toUri().toString();
		var diagnostic = byUri.get(expectedUri).get(0);
		assertEquals(new Range(new Position(11, 4), new Position(11, 11)), diagnostic.getRange());
	}

	@Test
	void rangeSpansTheSimpleNameOfATypeAndOfANestedType() {
		var top = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.b.Outer", "src/main/java/A.java:1:14",
				"unused", false);
		var nested = new AnalysisIssue(IssueType.UNUSED_VISIBILITY, Severity.INFO, "a.b.Outer.In",
				"src/main/java/A.java:4:22", "unused", true);
		var report = new AnalysisReport(List.of(top, nested), "two", true);

		var diagnostics = mapper.map(report, tempDir).values().iterator().next();

		assertEquals(new Range(new Position(0, 13), new Position(0, 18)), diagnostics.get(0).getRange());
		assertEquals(new Range(new Position(3, 21), new Position(3, 23)), diagnostics.get(1).getRange());
	}

	@Test
	void dependencyFindingWithoutAColumnStaysAtTheStartOfItsLine() {
		var issue = new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.WARNING, "org.example:lib", "pom.xml:20",
				"unused", true);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var diagnostic = mapper.map(report, tempDir).values().iterator().next().get(0);

		assertEquals(new Range(new Position(19, 0), new Position(19, 0)), diagnostic.getRange());
	}

	@Test
	void lineOnlyLocationKeepsColumnZero() {
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.ERROR, "Foo", "src/main/java/Foo.java:3",
				"unused", false);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var byUri = mapper.map(report, tempDir);

		var diagnostic = byUri.values().iterator().next().get(0);
		assertEquals(new Range(new Position(2, 0), new Position(2, 0)), diagnostic.getRange());
		assertEquals(DiagnosticSeverity.Error, diagnostic.getSeverity());
	}

	@Test
	void digitSuffixTooLongForAnIntIsTreatedAsPartOfThePath() {
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.INFO, "Foo",
				"src/main/java/Foo.java:99999999999", "unused", false);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var byUri = mapper.map(report, tempDir);

		var expectedUri = tempDir.resolve("src/main/java/Foo.java:99999999999").toUri().toString();
		var diagnostic = byUri.get(expectedUri).get(0);
		assertEquals(new Range(new Position(0, 0), new Position(0, 0)), diagnostic.getRange());
	}

	@Test
	void pathContainingANewlineStillMaps() {
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.INFO, "Foo", "src/main/java/we\nird.java:2",
				"unused", false);
		var report = new AnalysisReport(List.of(issue), "one", true);

		var byUri = mapper.map(report, tempDir);

		var expectedUri = tempDir.resolve("src/main/java/we\nird.java").toUri().toString();
		var diagnostic = byUri.get(expectedUri).get(0);
		assertEquals(new Range(new Position(1, 0), new Position(1, 0)), diagnostic.getRange());
	}

	@Test
	void groupsIssuesFromTheSameFileUnderOneUri() {
		var first = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "Foo#a", "src/main/java/Foo.java:1",
				"a", false);
		var second = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "Foo#b", "src/main/java/Foo.java:2",
				"b", false);
		var other = new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.INFO, "org.example:lib", "pom.xml", "c",
				false);
		var report = new AnalysisReport(List.of(first, second, other), "three", true);

		var byUri = mapper.map(report, tempDir);

		assertEquals(2, byUri.size());
		assertEquals(2, byUri.get(tempDir.resolve("src/main/java/Foo.java").toUri().toString()).size());
		assertEquals(1, byUri.get(tempDir.resolve("pom.xml").toUri().toString()).size());
	}

	@Test
	void mapsEverySeverity() {
		for (var severity : Severity.values()) {
			var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, severity, "Foo", "Foo.java", "m", false);
			var report = new AnalysisReport(List.of(issue), "one", true);

			var diagnostic = mapper.map(report, tempDir).values().iterator().next().get(0);

			var expected = switch (severity) {
				case INFO -> DiagnosticSeverity.Information;
				case WARNING -> DiagnosticSeverity.Warning;
				case ERROR -> DiagnosticSeverity.Error;
			};
			assertEquals(expected, diagnostic.getSeverity());
		}
	}

}
