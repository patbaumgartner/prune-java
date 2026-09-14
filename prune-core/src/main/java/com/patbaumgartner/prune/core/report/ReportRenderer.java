package com.patbaumgartner.prune.core.report;

import java.util.stream.Collectors;

public final class ReportRenderer {

	public String render(AnalysisReport report, OutputFormat format) {
		return switch (format) {
			case TERMINAL -> renderTerminal(report);
			case GITHUB_ANNOTATION -> renderGithubAnnotation(report);
			case JSON -> renderJson(report);
		};
	}

	// The summary is the only place that names skipped files, baseline suppressions, and
	// applied autofixes, so it stays even when no issue is left to list.
	private String renderTerminal(AnalysisReport report) {
		String kept = report.kept()
			.stream()
			.map(symbol -> "+ [KEPT] " + escapeControlCharacters(symbol.location()) + " :: "
					+ escapeControlCharacters(symbol.message()) + " [" + escapeControlCharacters(symbol.guard()) + "]")
			.collect(Collectors.joining(System.lineSeparator()));
		String summary = "Summary: " + escapeControlCharacters(report.summary());
		if (report.issues().isEmpty()) {
			return (kept.isEmpty() ? "" : kept + System.lineSeparator())
					+ "No unused code detected (conservative mode)." + System.lineSeparator() + summary;
		}

		String issues = report.issues()
			.stream()
			.map(issue -> "- [" + issue.severity() + "] " + escapeControlCharacters(issue.location()) + " :: "
					+ escapeControlCharacters(issue.message()))
			.collect(Collectors.joining(System.lineSeparator()));

		return (kept.isEmpty() ? "" : kept + System.lineSeparator()) + issues + System.lineSeparator() + summary;
	}

	private String renderGithubAnnotation(AnalysisReport report) {
		if (report.issues().isEmpty()) {
			return "::notice::No unused code detected (conservative mode). " + escapeGithubData(report.summary());
		}

		return report.issues()
			.stream()
			.map(issue -> "::warning " + githubProperties(issue) + "::" + escapeGithubData(issue.message()))
			.collect(Collectors.joining(System.lineSeparator()));
	}

	private String githubProperties(AnalysisIssue issue) {
		SourceLocation location = SourceLocation.parse(issue.location());
		StringBuilder properties = new StringBuilder("file=").append(escapeGithubProperty(location.path()));
		if (location.hasLine()) {
			properties.append(",line=").append(location.line());
			if (location.hasColumn()) {
				properties.append(",col=").append(location.column());
			}
		}
		return properties.toString();
	}

	private String renderJson(AnalysisReport report) {
		String issuesJson = report.issues()
			.stream()
			.map(issue -> "{\"type\":\"" + escapeJson(issue.type().name()) + "\",\"severity\":\""
					+ escapeJson(issue.severity().name()) + "\",\"symbol\":\"" + escapeJson(issue.symbol())
					+ "\",\"location\":\"" + escapeJson(issue.location()) + "\",\"message\":\""
					+ escapeJson(issue.message()) + "\",\"autoFixable\":" + issue.autoFixable() + "}")
			.collect(Collectors.joining(","));
		String keptJson = report.kept()
			.stream()
			.map(kept -> "{\"type\":\"" + escapeJson(kept.type().name()) + "\",\"symbol\":\""
					+ escapeJson(kept.symbol()) + "\",\"location\":\"" + escapeJson(kept.location()) + "\",\"guard\":\""
					+ escapeJson(kept.guard()) + "\",\"message\":\"" + escapeJson(kept.message()) + "\"}")
			.collect(Collectors.joining(","));

		return "{\"summary\":\"" + escapeJson(report.summary()) + "\",\"conservativeMode\":" + report.conservativeMode()
				+ ",\"issues\":[" + issuesJson + "],\"kept\":[" + keptJson + "]}";
	}

	private String escapeJson(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20) {
						escaped.append(String.format("\\u%04x", (int) c));
					}
					else {
						escaped.append(c);
					}
				}
			}
		}
		return escaped.toString();
	}

	private String escapeGithubData(String value) {
		return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
	}

	private String escapeGithubProperty(String value) {
		return escapeGithubData(value).replace(":", "%3A").replace(",", "%2C");
	}

	// C1 controls, bidirectional overrides, and the other invisible format characters are
	// legal in Java identifiers and can reorder or split a terminal line just like an
	// ANSI sequence.
	private String escapeControlCharacters(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (Character.getType(c)) {
				case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
					escaped.append(String.format("\\u%04x", (int) c));
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

}
