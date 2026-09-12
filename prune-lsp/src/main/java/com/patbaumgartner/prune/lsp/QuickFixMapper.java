package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.FixAction;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.SourceLocation;
import com.patbaumgartner.prune.core.source.LineMap;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QuickFixMapper {

	public List<CodeAction> quickFixes(String uri, List<Diagnostic> requested, AnalysisReport report, Path projectRoot,
			AutofixEngine engine) {
		List<CodeAction> actions = new ArrayList<>();
		for (Diagnostic diagnostic : requested) {
			if (!DiagnosticMapper.SOURCE.equals(diagnostic.getSource()) || diagnostic.getCode() == null
					|| diagnostic.getMessage() == null) {
				continue;
			}
			AnalysisIssue issue = matchingIssue(uri, diagnostic, report, projectRoot);
			if (issue == null || !issue.autoFixable()) {
				continue;
			}
			List<FixAction> fixes = engine.plan(new AnalysisReport(List.of(issue), report.summary(), true)).actions();
			if (fixes.isEmpty()) {
				continue;
			}
			Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
			Map<Path, LineMap> maps = new HashMap<>();
			String title = null;
			for (FixAction fix : fixes) {
				LineMap map = maps.computeIfAbsent(fix.file(), file -> lineMapIfUnchanged(file, fix));
				if (map == null) {
					changes.clear();
					break;
				}
				TextEdit edit = new TextEdit(
						new Range(position(map, fix.startOffset()), position(map, fix.endOffset())), fix.replacement());
				changes.computeIfAbsent(fix.file().toUri().toString(), key -> new ArrayList<>()).add(edit);
				if (fix.issue().equals(issue) && title == null && !fix.description().startsWith("Remove import ")) {
					title = fix.description();
				}
			}
			if (changes.isEmpty()) {
				continue;
			}
			CodeAction action = new CodeAction(title != null ? title : fixes.get(0).description());
			action.setKind(CodeActionKind.QuickFix);
			action.setDiagnostics(List.of(diagnostic));
			action.setEdit(new WorkspaceEdit(changes));
			actions.add(action);
		}
		return actions;
	}

	// Every edit of one fix targets the same file, so the map is built once and only when
	// the text still matches what the engine planned against.
	private static LineMap lineMapIfUnchanged(Path file, FixAction fix) {
		String content;
		try {
			content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		}
		catch (IOException unreadable) {
			return null;
		}
		return content.startsWith(fix.original(), fix.startOffset()) ? new LineMap(content) : null;
	}

	// Two members of different nested types can share a name and therefore a message, so
	// the diagnostic's line disambiguates when the client sends one.
	private static AnalysisIssue matchingIssue(String uri, Diagnostic diagnostic, AnalysisReport report,
			Path projectRoot) {
		String code = diagnostic.getCode().isLeft() ? diagnostic.getCode().getLeft() : null;
		String message = diagnostic.getMessage().isLeft() ? diagnostic.getMessage().getLeft() : null;
		Integer line = diagnostic.getRange() == null || diagnostic.getRange().getStart() == null ? null
				: diagnostic.getRange().getStart().getLine();
		for (AnalysisIssue issue : report.issues()) {
			SourceLocation location = SourceLocation.parse(issue.location());
			String issueUri = projectRoot.resolve(location.path()).normalize().toUri().toString();
			if (issueUri.equals(uri) && issue.type().name().equals(code) && issue.message().equals(message)
					&& (line == null || line == Math.max(0, location.line() - 1))) {
				return issue;
			}
		}
		return null;
	}

	private static Position position(LineMap map, int offset) {
		return new Position(map.lineOf(offset) - 1, map.columnOf(offset) - 1);
	}

}
