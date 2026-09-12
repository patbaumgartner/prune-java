package com.patbaumgartner.prune.core.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "path[:line[:column]]" with one-based positions; 0 means absent. Only trailing digit
// runs are positions, so "C:\src\A.java:12" keeps its drive letter, and a run longer than
// nine digits is part of the path because it cannot be a position.
public record SourceLocation(String path, int line, int column) {

	private static final Pattern FORMAT = Pattern.compile("^(.*?)(?::(\\d{1,9}))?(?::(\\d{1,9}))?$", Pattern.DOTALL);

	public static SourceLocation parse(String location) {
		Matcher matcher = FORMAT.matcher(location);
		// Every group is optional and DOTALL lets the path span any character, so this
		// always matches.
		matcher.matches();
		return new SourceLocation(matcher.group(1), position(matcher.group(2)), position(matcher.group(3)));
	}

	public static SourceLocation of(String path, int line, int column) {
		return new SourceLocation(path, line, column);
	}

	private static int position(String digits) {
		return digits == null ? 0 : Integer.parseInt(digits);
	}

	public boolean hasLine() {
		return line > 0;
	}

	public boolean hasColumn() {
		return column > 0;
	}

	public String format() {
		if (!hasLine()) {
			return path;
		}
		return hasColumn() ? path + ":" + line + ":" + column : path + ":" + line;
	}
}
