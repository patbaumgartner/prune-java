package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.source.LineMap;

import java.util.function.Predicate;

final class LineEdits {

	private LineEdits() {
	}

	// Whole-line deletion of firstLine..lastLine (one-based, inclusive) plus directly
	// attached leading comment lines. One neighbouring blank line goes too when the
	// removal would otherwise leave a doubled blank line, a blank line before a closing
	// brace or tag, or a blank line right after an opening one.
	static int[] deleteLines(String content, LineMap map, int firstLine, int lastLine,
			Predicate<String> attachedComment) {
		int first = firstLine;
		while (first > 1 && attachedComment.test(lineText(content, map, first - 1).strip())) {
			first--;
		}
		int last = lastLine;
		int lineCount = map.lineCount();
		String before = first > 1 ? lineText(content, map, first - 1).strip() : null;
		String after = last < lineCount ? lineText(content, map, last + 1).strip() : null;
		boolean blankBefore = before != null && before.isEmpty();
		boolean blankAfter = after != null && after.isEmpty();
		if (blankBefore && (after == null || blankAfter || after.startsWith("}") || after.startsWith("</"))) {
			first--;
		}
		else if (blankAfter && (before == null || before.endsWith("{") || before.endsWith(">"))) {
			last++;
		}
		return new int[] { map.startOf(first), map.startOf(last + 1) };
	}

	static boolean aloneOnLines(String content, LineMap map, int start, int end) {
		String leading = content.substring(map.startOf(map.lineOf(start)), start);
		int lastLine = map.lineOf(Math.max(start, end - 1));
		String trailing = content.substring(end, lineEnd(content, map, lastLine)).strip();
		return leading.isBlank() && (trailing.isEmpty() || trailing.startsWith("//"));
	}

	static String lineText(String content, LineMap map, int line) {
		return content.substring(map.startOf(line), lineEnd(content, map, line));
	}

	private static int lineEnd(String content, LineMap map, int line) {
		int end = map.startOf(line + 1);
		while (end > map.startOf(line) && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
			end--;
		}
		return end;
	}

}
