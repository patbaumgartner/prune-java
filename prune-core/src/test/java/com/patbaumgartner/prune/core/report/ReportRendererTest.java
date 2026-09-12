package com.patbaumgartner.prune.core.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportRendererTest {

	@Test
	void rendersEmptyTerminalReport() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "none", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("No unused code detected (conservative mode)." + System.lineSeparator() + "Summary: none", output);
	}

	// Skipped files, baseline suppressions, and applied autofixes are only reported in
	// the summary, and matter most when no issue is left to list.
	@Test
	void emptyTerminalReportKeepsTheSummaryAndEscapesIt() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(),
				"Applied 2 autofix(es) in 1 file(s); 0 issue(s) remain (conservative mode).\u001b[2K", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("No unused code detected (conservative mode)." + System.lineSeparator()
				+ "Summary: Applied 2 autofix(es) in 1 file(s); 0 issue(s) remain (conservative mode).\\u001b[2K",
				output);
	}

	@Test
	void rendersEmptyJsonReport() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "none", true);

		var output = renderer.render(report, OutputFormat.JSON);

		assertEquals("{\"summary\":\"none\",\"conservativeMode\":true,\"issues\":[],\"kept\":[]}", output);
	}

	@Test
	void rendersEmptySarifReportWithEveryRuleAndTheSummary() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "none", true);

		var output = renderer.render(report, OutputFormat.SARIF);

		assertEquals("{\"$schema\":\"https://json.schemastore.org/sarif-2.1.0.json\",\"version\":\"2.1.0\","
				+ "\"runs\":[{\"tool\":{\"driver\":{\"name\":\"prune-java\","
				+ "\"informationUri\":\"https://github.com/patbaumgartner/prune-java\",\"rules\":["
				+ "{\"id\":\"UNUSED_CLASS\",\"shortDescription\":{\"text\":\"A package-private or non-public nested type is never referenced.\"}},"
				+ "{\"id\":\"UNUSED_METHOD\",\"shortDescription\":{\"text\":\"A private method is never referenced.\"}},"
				+ "{\"id\":\"UNUSED_FIELD\",\"shortDescription\":{\"text\":\"A private field is never referenced.\"}},"
				+ "{\"id\":\"UNUSED_VISIBILITY\",\"shortDescription\":{\"text\":\"A public class is only used from its own package.\"}},"
				+ "{\"id\":\"UNUSED_DEPENDENCY\",\"shortDescription\":{\"text\":\"A declared dependency is never imported.\"}}"
				+ "]}},\"results\":[],\"properties\":{\"summary\":\"none\",\"conservativeMode\":true}}]}", output);
	}

	@Test
	void sarifResultsCarryRuleLevelLocationFingerprintAndAutofixability() {
		var renderer = new ReportRenderer();
		var method = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "a.B#m",
				"src/main/java/a/B.java:4:18", "Private method m is never used", true);
		var dependency = new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.ERROR, "org.x:y", "pom.xml:31",
				"Dependency org.x:y is declared but never imported", true);
		var type = new AnalysisIssue(IssueType.UNUSED_VISIBILITY, Severity.INFO, "a.C", "src/main/java/a/C.java",
				"Class C is only used from its own package", false);
		var report = new AnalysisReport(List.of(method, dependency, type), "3 issue(s)", true);

		var output = renderer.render(report, OutputFormat.SARIF);

		var results = output.substring(output.indexOf("\"results\":"), output.indexOf(",\"properties\":{\"summary\""));
		assertEquals("\"results\":["
				+ "{\"ruleId\":\"UNUSED_METHOD\",\"level\":\"warning\",\"message\":{\"text\":\"Private method m is never used\"},"
				+ "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"src/main/java/a/B.java\",\"uriBaseId\":\"%SRCROOT%\"},"
				+ "\"region\":{\"startLine\":4,\"startColumn\":18}}}],"
				+ "\"partialFingerprints\":{\"symbol\":\"a.B#m\"},\"properties\":{\"autoFixable\":true}},"
				+ "{\"ruleId\":\"UNUSED_DEPENDENCY\",\"level\":\"error\",\"message\":{\"text\":\"Dependency org.x:y is declared but never imported\"},"
				+ "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"pom.xml\",\"uriBaseId\":\"%SRCROOT%\"},"
				+ "\"region\":{\"startLine\":31}}}],"
				+ "\"partialFingerprints\":{\"symbol\":\"org.x:y\"},\"properties\":{\"autoFixable\":true}},"
				+ "{\"ruleId\":\"UNUSED_VISIBILITY\",\"level\":\"note\",\"message\":{\"text\":\"Class C is only used from its own package\"},"
				+ "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"src/main/java/a/C.java\",\"uriBaseId\":\"%SRCROOT%\"}}}],"
				+ "\"partialFingerprints\":{\"symbol\":\"a.C\"},\"properties\":{\"autoFixable\":false}}" + "]",
				results);
	}

	@Test
	void sarifOutputEscapesJsonAndEncodesEveryPathAsARelativeUri() {
		var renderer = new ReportRenderer();
		var spaced = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.B\"", "src/my app/a b/C#1.java:2:1",
				"Class \"B\" \\ is\nnever referenced", false);
		var windows = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.D",
				"src\\main\\java\\a\\D%.java:3", "Class D is never referenced", false);
		var report = new AnalysisReport(List.of(spaced, windows), "summary \"quoted\"\u0001", true);

		var output = renderer.render(report, OutputFormat.SARIF);

		assertTrue(output.contains("\"message\":{\"text\":\"Class \\\"B\\\" \\\\ is\\nnever referenced\"}"), output);
		assertTrue(output.contains("\"uri\":\"src/my%20app/a%20b/C%231.java\",\"uriBaseId\":\"%SRCROOT%\"},"
				+ "\"region\":{\"startLine\":2,\"startColumn\":1}"), output);
		assertTrue(output.contains(
				"\"uri\":\"src/main/java/a/D%25.java\",\"uriBaseId\":\"%SRCROOT%\"}," + "\"region\":{\"startLine\":3}"),
				output);
		assertTrue(output.contains("\"partialFingerprints\":{\"symbol\":\"a.B\\\"\"}"), output);
		assertTrue(
				output.endsWith(
						"\"properties\":{\"summary\":\"summary \\\"quoted\\\"\\u0001\",\"conservativeMode\":true}}]}"),
				output);
	}

	@Test
	void sarifOutputOmitsKeptSymbolsLikeTheGithubFormat() {
		var renderer = new ReportRenderer();
		var kept = new KeptSymbol(IssueType.UNUSED_METHOD, "a.B#m", "src/main/java/a/B.java:4:18", "annotation",
				"Private method m is kept: it carries an annotation");
		var report = new AnalysisReport(List.of(), "none", true, List.of(kept));

		var output = renderer.render(report, OutputFormat.SARIF);

		assertFalse(output.contains("annotation"), output);
		assertTrue(output.contains("\"results\":[]"), output);
	}

	@Test
	void rendersNoticeWhenGithubOutputHasNoIssues() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "none", true);

		var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

		assertEquals("::notice::No unused code detected (conservative mode). none", output);
	}

	@Test
	void emptyGithubReportEscapesTheSummaryAsAnnotationData() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "Skipped 1 file(s): A%.java\r\n::error::spoof", true);

		var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

		assertEquals(
				"::notice::No unused code detected (conservative mode). Skipped 1 file(s): A%25.java%0D%0A::error::spoof",
				output);
	}

	@Test
	void escapesJsonContentInJsonOutput() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "A\\\"B", "src/main/java/A.java",
				"message with \\ and \"", true);
		var report = new AnalysisReport(List.of(issue), "summary \"quoted\"", true);

		var output = renderer.render(report, OutputFormat.JSON);

		assertEquals(
				"{\"summary\":\"summary \\\"quoted\\\"\",\"conservativeMode\":true,\"issues\":[{\"type\":\"UNUSED_METHOD\",\"severity\":\"WARNING\",\"symbol\":\"A\\\\\\\"B\",\"location\":\"src/main/java/A.java\",\"message\":\"message with \\\\ and \\\"\",\"autoFixable\":true}],\"kept\":[]}",
				output);
	}

	@Test
	void escapesControlCharactersInEveryJsonField() {
		var renderer = new ReportRenderer();
		var content = "\"\\\b\f\n\r\t\u0000\u001f%";
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.INFO, content, content, content, false);
		var report = new AnalysisReport(List.of(issue), content, false);

		var output = renderer.render(report, OutputFormat.JSON);

		var escaped = "\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f%";
		assertEquals("{\"summary\":\"" + escaped + "\",\"conservativeMode\":false,\"issues\":["
				+ "{\"type\":\"UNUSED_CLASS\",\"severity\":\"INFO\",\"symbol\":\"" + escaped + "\",\"location\":\""
				+ escaped + "\",\"message\":\"" + escaped + "\",\"autoFixable\":false}],\"kept\":[]}", output);
	}

	@Test
	void jsonOutputListsKeptSymbolsWithTheirGuardAndEscapesEveryField() {
		var renderer = new ReportRenderer();
		var kept = new KeptSymbol(IssueType.UNUSED_METHOD, "a.B#\"m\"", "src/main/java/a/B.java:4:18", "plug\\in",
				"Private method m is kept: it carries an annotation\n");
		var report = new AnalysisReport(List.of(), "none", true, List.of(kept));

		var output = renderer.render(report, OutputFormat.JSON);

		assertEquals("{\"summary\":\"none\",\"conservativeMode\":true,\"issues\":[],\"kept\":["
				+ "{\"type\":\"UNUSED_METHOD\",\"symbol\":\"a.B#\\\"m\\\"\",\"location\":\"src/main/java/a/B.java:4:18\","
				+ "\"guard\":\"plug\\\\in\",\"message\":\"Private method m is kept: it carries an annotation\\n\"}]}",
				output);
	}

	@Test
	void terminalOutputListsKeptSymbolsBeforeIssuesAndEscapesControlCharacters() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.B", "src/main/java/a/B.java:3:7",
				"Class B is never referenced", false);
		var kept = new KeptSymbol(IssueType.UNUSED_METHOD, "a.C#m", "src/main/java/a/C.java:4:18", "annota\u001Btion",
				"Private method m is kept: it carries an annotation\u202E");
		var report = new AnalysisReport(List.of(issue), "1 issue(s)", true, List.of(kept));

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("+ [KEPT] src/main/java/a/C.java:4:18 :: Private method m is kept: it carries an annotation\\u202e"
				+ " [annota\\u001btion]" + System.lineSeparator()
				+ "- [WARNING] src/main/java/a/B.java:3:7 :: Class B is never referenced" + System.lineSeparator()
				+ "Summary: 1 issue(s)", output);
	}

	@Test
	void terminalOutputWithOnlyKeptSymbolsStillEndsWithTheNoIssuesLine() {
		var renderer = new ReportRenderer();
		var kept = new KeptSymbol(IssueType.UNUSED_FIELD, "a.C#f", "src/main/java/a/C.java:5:17", "serialization",
				"Private field f is kept: the class is Serializable");
		var report = new AnalysisReport(List.of(), "none", true, List.of(kept));

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("+ [KEPT] src/main/java/a/C.java:5:17 :: Private field f is kept: the class is Serializable"
				+ " [serialization]" + System.lineSeparator() + "No unused code detected (conservative mode)."
				+ System.lineSeparator() + "Summary: none", output);
	}

	@Test
	void githubOutputIgnoresKeptSymbols() {
		var renderer = new ReportRenderer();
		var kept = new KeptSymbol(IssueType.UNUSED_FIELD, "a.C#f", "src/main/java/a/C.java:5:17", "serialization",
				"Private field f is kept: the class is Serializable");
		var report = new AnalysisReport(List.of(), "none", true, List.of(kept));

		assertEquals("::notice::No unused code detected (conservative mode). none",
				renderer.render(report, OutputFormat.GITHUB_ANNOTATION));
	}

	@Test
	void escapesGithubWorkflowCommandSpecialCharacters() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "field", "A.java",
				"line1%\nline2\rline3", false);
		var report = new AnalysisReport(List.of(issue), "n/a", true);

		var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

		assertEquals("::warning file=A.java::line1%25%0Aline2%0Dline3", output);
	}

	@Test
	void escapesPropertySeparatorsInGithubFileLocation() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Evil",
				"A.java:12%,line=99,col=1,title=spoofed\r\n::error::", "real message", false);
		var report = new AnalysisReport(List.of(issue), "n/a", true);

		var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

		assertEquals("::warning file=A.java%3A12%25%2Cline=99%2Ccol=1%2Ctitle=spoofed"
				+ "%0D%0A%3A%3Aerror%3A%3A::real message", output);
	}

	@Test
	void escapesControlCharactersInTerminalOutput() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.ERROR, "m", "A.java:1",
				"real finding\u001b[2K\rALL CLEAR", false);
		var report = new AnalysisReport(List.of(issue), "n/a", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("- [ERROR] A.java:1 :: real finding\\u001b[2K\\u000dALL CLEAR" + System.lineSeparator()
				+ "Summary: n/a", output);
	}

	@Test
	void escapesTerminalLocationAndSummaryControls() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "field", "A\t\u001b[2K\r\n.java",
				"unused", false);
		var report = new AnalysisReport(List.of(issue), "summary\b\f\u0000\u007f", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("- [WARNING] A\\u0009\\u001b[2K\\u000d\\u000a.java :: unused" + System.lineSeparator()
				+ "Summary: summary\\u0008\\u000c\\u0000\\u007f", output);
	}

	@Test
	void escapesBidirectionalOverridesAndOtherInvisibleCharactersInTerminalOutput() {
		var renderer = new ReportRenderer();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Dead\u202Ex", "Dead\u0085x.java:3",
				"Class Dead\u202E\u2066\u200B\u2028\u00ADx is never referenced \u00e9", false);
		var report = new AnalysisReport(List.of(issue), "n/a", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals(
				"- [WARNING] Dead\\u0085x.java:3 :: Class Dead\\u202e\\u2066\\u200b\\u2028\\u00adx is never referenced \u00e9"
						+ System.lineSeparator() + "Summary: n/a",
				output);
	}

	@Test
	void emitsLineAndColumnAnnotationPropertiesFromTheLocationSuffix() {
		var renderer = new ReportRenderer();
		var withColumn = new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING, "A#m",
				"src/main/java/A.java:12:5", "unused", true);
		var lineOnly = new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.WARNING, "g:a", "pom.xml:30", "unused",
				true);
		var report = new AnalysisReport(List.of(withColumn, lineOnly), "n/a", true);

		var output = renderer.render(report, OutputFormat.GITHUB_ANNOTATION);

		assertEquals("::warning file=src/main/java/A.java,line=12,col=5::unused" + System.lineSeparator()
				+ "::warning file=pom.xml,line=30::unused", output);
	}

	@Test
	void terminalOutputListsEveryIssueWithSeverityLocationAndMessage() {
		var renderer = new ReportRenderer();
		var first = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "a.B", "src/main/java/a/B.java:3:7",
				"Class B is never referenced", false);
		var second = new AnalysisIssue(IssueType.UNUSED_VISIBILITY, Severity.INFO, "a.C", "src/main/java/a/C.java:1:14",
				"can be package-private", true);
		var report = new AnalysisReport(List.of(first, second), "2 issue(s)", true);

		var output = renderer.render(report, OutputFormat.TERMINAL);

		assertEquals("- [WARNING] src/main/java/a/B.java:3:7 :: Class B is never referenced" + System.lineSeparator()
				+ "- [INFO] src/main/java/a/C.java:1:14 :: can be package-private" + System.lineSeparator()
				+ "Summary: 2 issue(s)", output);
	}

	@Test
	void rendersEmptyReportsInTerminalAndJsonFormats() {
		var renderer = new ReportRenderer();
		var report = new AnalysisReport(List.of(), "none", true);

		assertEquals("No unused code detected (conservative mode)." + System.lineSeparator() + "Summary: none",
				renderer.render(report, OutputFormat.TERMINAL));
		assertEquals("{\"summary\":\"none\",\"conservativeMode\":true,\"issues\":[],\"kept\":[]}",
				renderer.render(report, OutputFormat.JSON));
	}

}
