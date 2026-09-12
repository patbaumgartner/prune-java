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

    private String renderTerminal(AnalysisReport report) {
        if (report.issues().isEmpty()) {
            return "No unused code detected (conservative mode).";
        }

        String issues = report.issues().stream()
                .map(issue -> "- [" + issue.severity() + "] " + escapeControlCharacters(issue.location()) + " :: "
                        + escapeControlCharacters(issue.message()))
                .collect(Collectors.joining(System.lineSeparator()));

        return issues + System.lineSeparator() + "Summary: " + escapeControlCharacters(report.summary());
    }

    private String renderGithubAnnotation(AnalysisReport report) {
        if (report.issues().isEmpty()) {
            return "::notice::No unused code detected (conservative mode).";
        }

        return report.issues().stream()
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
        String issuesJson = report.issues().stream()
                .map(issue -> "{\"type\":\"" + escapeJson(issue.type().name()) + "\",\"severity\":\""
                        + escapeJson(issue.severity().name())
                        + "\",\"symbol\":\"" + escapeJson(issue.symbol()) + "\",\"location\":\""
                        + escapeJson(issue.location()) + "\",\"message\":\"" + escapeJson(issue.message())
                        + "\",\"autoFixable\":"
                        + issue.autoFixable() + "}")
                .collect(Collectors.joining(","));

        return "{\"summary\":\"" + escapeJson(report.summary()) + "\",\"conservativeMode\":"
                + report.conservativeMode() + ",\"issues\":[" + issuesJson + "]}";
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
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private String escapeGithubData(String value) {
        return value
                .replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }

    private String escapeGithubProperty(String value) {
        return escapeGithubData(value)
                .replace(":", "%3A")
                .replace(",", "%2C");
    }

    private String escapeControlCharacters(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
