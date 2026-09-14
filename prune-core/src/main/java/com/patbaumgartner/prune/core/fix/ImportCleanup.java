package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.source.JavaLexer;
import com.patbaumgartner.prune.core.source.LineMap;
import com.patbaumgartner.prune.core.source.TextSearch;
import com.patbaumgartner.prune.core.source.Token;
import com.patbaumgartner.prune.core.source.TokenKind;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Removing a member can leave an import behind that nothing else in the file needs. Such
// an import is removed with the member so that builds enforcing unused-import rules keep
// passing. Imports that were already unused before the fix are left alone: they are not
// this fix's business.
final class ImportCleanup {

	private ImportCleanup() {
	}

	static List<FixAction> plan(Path file, String content, List<FixAction> memberActions) {
		int firstEdit = memberActions.stream().mapToInt(FixAction::startOffset).min().orElse(content.length());
		String edited = applyAll(content, memberActions);
		LineMap map = new LineMap(content);
		List<ImportStatement> orphaned = new ArrayList<>();
		for (ImportStatement statement : imports(content)) {
			if (statement.end > firstEdit || statement.simpleName == null) {
				continue;
			}
			String beforeWithoutImport = blank(content, statement.start, statement.end);
			String afterWithoutImport = blank(edited, statement.start, statement.end);
			if (TextSearch.containsWord(beforeWithoutImport, statement.simpleName)
					&& !TextSearch.containsWord(afterWithoutImport, statement.simpleName)
					&& LineEdits.aloneOnLines(content, map, statement.start, statement.end)) {
				orphaned.add(statement);
			}
		}

		// Orphaned imports on consecutive lines go as one block so the blank-line
		// handling sees the whole gap instead of leaving one empty line per removed
		// import behind.
		List<FixAction> removals = new ArrayList<>();
		int index = 0;
		while (index < orphaned.size()) {
			int firstLine = map.lineOf(orphaned.get(index).start);
			int lastLine = map.lineOf(orphaned.get(index).end - 1);
			StringBuilder names = new StringBuilder(orphaned.get(index).name);
			while (index + 1 < orphaned.size() && map.lineOf(orphaned.get(index + 1).start) == lastLine + 1) {
				index++;
				lastLine = map.lineOf(orphaned.get(index).end - 1);
				names.append(", ").append(orphaned.get(index).name);
			}
			int[] span = LineEdits.deleteLines(content, map, firstLine, lastLine, line -> false);
			removals.add(new FixAction(memberActions.get(0).issue(), file, span[0], span[1],
					content.substring(span[0], span[1]), "", "Remove import " + names + " left unused"));
			index++;
		}
		return removals;
	}

	private static String applyAll(String content, List<FixAction> actions) {
		StringBuilder edited = new StringBuilder(content);
		List<FixAction> descending = new ArrayList<>(actions);
		descending.sort((a, b) -> Integer.compare(b.startOffset(), a.startOffset()));
		for (FixAction action : descending) {
			edited.replace(action.startOffset(), action.endOffset(), action.replacement());
		}
		return edited.toString();
	}

	private static String blank(String content, int start, int end) {
		return content.substring(0, start) + " ".repeat(end - start) + content.substring(end);
	}

	private static List<ImportStatement> imports(String content) {
		List<Token> tokens = JavaLexer.tokenize(content).stream().filter(t -> t.kind() != TokenKind.COMMENT).toList();
		List<ImportStatement> statements = new ArrayList<>();
		int i = 0;
		while (i < tokens.size()) {
			if (!tokens.get(i).isKeyword("import")) {
				i++;
				continue;
			}
			StringBuilder name = new StringBuilder();
			String last = null;
			boolean wildcard = false;
			int j = i + 1;
			for (; j < tokens.size() && !tokens.get(j).isPunctuation(';'); j++) {
				Token token = tokens.get(j);
				if (token.kind() == TokenKind.IDENTIFIER) {
					last = token.text();
				}
				else if (token.isPunctuation('*')) {
					wildcard = true;
				}
				if (!token.isKeyword("static")) {
					name.append(token.text());
				}
				else {
					name.append("static ");
				}
			}
			if (j >= tokens.size()) {
				break;
			}
			statements.add(new ImportStatement(tokens.get(i).start(), tokens.get(j).end(), name.toString(),
					wildcard ? null : last));
			i = j;
		}
		return statements;
	}

	// A wildcard import has no simple name to look for and is never removed.
	private record ImportStatement(int start, int end, String name, @Nullable String simpleName) {
	}

}
