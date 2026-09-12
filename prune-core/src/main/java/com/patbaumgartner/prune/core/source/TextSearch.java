package com.patbaumgartner.prune.core.source;

import java.util.Set;

public final class TextSearch {

	private TextSearch() {
	}

	// A whole-word match: the neighbours may not extend the identifier. Dots and dollar
	// signs are boundaries so "com.example.Foo" and "Outer$Inner" both count as
	// mentioning their parts.
	public static boolean containsWord(String text, String word) {
		if (word.isEmpty()) {
			return false;
		}
		int from = 0;
		while (true) {
			int index = text.indexOf(word, from);
			if (index < 0) {
				return false;
			}
			int after = index + word.length();
			boolean startsWord = index == 0 || !isIdentifierPart(text.charAt(index - 1));
			boolean endsWord = after == text.length() || !isIdentifierPart(text.charAt(after));
			if (startsWord && endsWord) {
				return true;
			}
			from = index + 1;
		}
	}

	// Adds every whole word of the text to the sink, using the same boundaries as
	// containsWord.
	public static void words(String text, Set<String> sink) {
		int start = -1;
		for (int i = 0; i <= text.length(); i++) {
			boolean part = i < text.length() && isIdentifierPart(text.charAt(i));
			if (part && start < 0) {
				start = i;
			}
			else if (!part && start >= 0) {
				sink.add(text.substring(start, i));
				start = -1;
			}
		}
	}

	private static boolean isIdentifierPart(char c) {
		return c != '$' && Character.isJavaIdentifierPart(c) && !Character.isIdentifierIgnorable(c);
	}

}
