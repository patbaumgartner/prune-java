package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.Severity;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticMapper {

    public static final String SOURCE = "prune-java";

    // Only trailing ":digits" segments are line/column, so "C:\src\A.java:12" keeps its drive letter.
    // Nine digits always fit an int; a longer run is a path segment, not a position.
    private static final Pattern LOCATION =
            Pattern.compile("^(.*?)(?::(\\d{1,9}))?(?::(\\d{1,9}))?$", Pattern.DOTALL);

    public Map<String, List<Diagnostic>> map(AnalysisReport report, Path projectRoot) {
        Map<String, List<Diagnostic>> byUri = new LinkedHashMap<>();
        for (AnalysisIssue issue : report.issues()) {
            Matcher matcher = LOCATION.matcher(issue.location());
            // Every group is optional and DOTALL lets the path span any character, so this always matches.
            matcher.matches();

            String uri = projectRoot.resolve(matcher.group(1)).normalize().toUri().toString();
            Position position = new Position(zeroBased(matcher.group(2)), zeroBased(matcher.group(3)));

            Diagnostic diagnostic = new Diagnostic(
                    new Range(position, position),
                    issue.message(),
                    toSeverity(issue.severity()),
                    SOURCE,
                    issue.type().name()
            );
            diagnostic.setTags(List.of(DiagnosticTag.Unnecessary));

            byUri.computeIfAbsent(uri, key -> new ArrayList<>()).add(diagnostic);
        }
        return byUri;
    }

    private static int zeroBased(String oneBased) {
        return oneBased == null ? 0 : Math.max(0, Integer.parseInt(oneBased) - 1);
    }

    private static DiagnosticSeverity toSeverity(Severity severity) {
        return switch (severity) {
            case INFO -> DiagnosticSeverity.Information;
            case WARNING -> DiagnosticSeverity.Warning;
            case ERROR -> DiagnosticSeverity.Error;
        };
    }
}
