package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.Severity;
import com.patbaumgartner.prune.core.report.SourceLocation;
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

public final class DiagnosticMapper {

    public static final String SOURCE = "prune-java";

    public Map<String, List<Diagnostic>> map(AnalysisReport report, Path projectRoot) {
        Map<String, List<Diagnostic>> byUri = new LinkedHashMap<>();
        for (AnalysisIssue issue : report.issues()) {
            SourceLocation location = SourceLocation.parse(issue.location());

            String uri = projectRoot.resolve(location.path()).normalize().toUri().toString();
            Position position = new Position(zeroBased(location.line()), zeroBased(location.column()));

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

    private static int zeroBased(int oneBased) {
        return Math.max(0, oneBased - 1);
    }

    private static DiagnosticSeverity toSeverity(Severity severity) {
        return switch (severity) {
            case INFO -> DiagnosticSeverity.Information;
            case WARNING -> DiagnosticSeverity.Warning;
            case ERROR -> DiagnosticSeverity.Error;
        };
    }
}
