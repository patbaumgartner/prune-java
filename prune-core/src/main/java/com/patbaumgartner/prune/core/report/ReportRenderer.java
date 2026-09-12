package com.patbaumgartner.prune.core.report;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class ReportRenderer {

	public String render(AnalysisReport report, OutputFormat format) {
		return switch (format) {
			case TERMINAL -> renderTerminal(report);
			case GITHUB_ANNOTATION -> renderGithubAnnotation(report);
			case JSON -> renderJson(report);
			case SARIF -> renderSarif(report);
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

	// SARIF 2.1.0 as GitHub code scanning ingests it: one rule per issue type, one
	// result per issue anchored at the checkout root, the symbol as a fingerprint so an
	// alert survives the line moving. Kept symbols are not results and are omitted.
	private String renderSarif(AnalysisReport report) {
		String rules = Arrays.stream(IssueType.values())
			.map(type -> "{\"id\":\"" + type.name() + "\",\"shortDescription\":{\"text\":\"" + describe(type) + "\"}}")
			.collect(Collectors.joining(","));
		String results = report.issues()
			.stream()
			.map(issue -> "{\"ruleId\":\"" + issue.type().name() + "\",\"level\":\"" + sarifLevel(issue.severity())
					+ "\",\"message\":{\"text\":\"" + escapeJson(issue.message()) + "\"},\"locations\":["
					+ sarifLocation(SourceLocation.parse(issue.location())) + "],\"partialFingerprints\":{\"symbol\":\""
					+ escapeJson(issue.symbol()) + "\"},\"properties\":{\"autoFixable\":" + issue.autoFixable() + "}}")
			.collect(Collectors.joining(","));
		return "{\"$schema\":\"https://json.schemastore.org/sarif-2.1.0.json\",\"version\":\"2.1.0\",\"runs\":[{"
				+ "\"tool\":{\"driver\":{\"name\":\"prune-java\","
				+ "\"informationUri\":\"https://github.com/patbaumgartner/prune-java\",\"rules\":[" + rules + "]}},"
				+ "\"results\":[" + results + "],\"properties\":{\"summary\":\"" + escapeJson(report.summary())
				+ "\",\"conservativeMode\":" + report.conservativeMode() + "}}]}";
	}

	private static String describe(IssueType type) {
		return switch (type) {
			case UNUSED_CLASS -> "A package-private or non-public nested type is never referenced.";
			case UNUSED_METHOD -> "A private method is never referenced.";
			case UNUSED_FIELD -> "A private field is never referenced.";
			case UNUSED_VISIBILITY -> "A public class is only used from its own package.";
			case UNUSED_DEPENDENCY -> "A declared dependency is never imported.";
		};
	}

	private static String sarifLevel(Severity severity) {
		return switch (severity) {
			case INFO -> "note";
			case WARNING -> "warning";
			case ERROR -> "error";
		};
	}

	private String sarifLocation(SourceLocation location) {
		StringBuilder region = new StringBuilder();
		if (location.hasLine()) {
			region.append(",\"region\":{\"startLine\":").append(location.line());
			if (location.hasColumn()) {
				region.append(",\"startColumn\":").append(location.column());
			}
			region.append('}');
		}
		return "{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"" + escapeJson(relativeUri(location.path()))
				+ "\",\"uriBaseId\":\"%SRCROOT%\"}" + region + "}}";
	}

	// A root-relative path as a URI reference: separators normalized, every segment
	// percent-encoded except the unreserved characters, so a space, a '#', or a '%' in a
	// file name cannot change what the location points at.
	private static String relativeUri(String path) {
		byte[] bytes = path.replace('\\', '/').getBytes(StandardCharsets.UTF_8);
		StringBuilder uri = new StringBuilder(bytes.length);
		for (byte b : bytes) {
			char c = (char) (b & 0xff);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
					|| c == '_' || c == '~' || c == '/') {
				uri.append(c);
			}
			else {
				uri.append('%')
					.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			}
		}
		return uri.toString();
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
						appendUnicodeEscape(escaped, c);
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
					appendUnicodeEscape(escaped, c);
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

	private static void appendUnicodeEscape(StringBuilder escaped, char c) {
		String hex = Integer.toHexString(c);
		escaped.append("\\u");
		for (int i = hex.length(); i < 4; i++) {
			escaped.append('0');
		}
		escaped.append(hex);
	}

}
