package com.patbaumgartner.prune.core.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleBuildParser {

	private static final String CONFIGURATIONS = "(implementation|api|compileOnly|compileOnlyApi|compile)";

	private static final Pattern STRING_NOTATION = Pattern
		.compile("^\\s*" + CONFIGURATIONS + "\\s*\\(?\\s*(['\"])([^'\"]+)\\2\\s*\\)?\\s*(\\{.*)?$");

	private static final Pattern MAP_NOTATION = Pattern.compile("^\\s*" + CONFIGURATIONS
			+ "\\s*\\(?\\s*group\\s*[:=]\\s*(['\"])([^'\"]+)\\2\\s*,\\s*name\\s*[:=]\\s*(['\"])([^'\"]+)\\4.*$");

	private GradleBuildParser() {
	}

	public static List<DeclaredDependency> parse(String buildFile, String script) {
		List<DeclaredDependency> dependencies = new ArrayList<>();
		String[] lines = stripComments(script).split("\r?\n|\r", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher string = STRING_NOTATION.matcher(line);
			if (string.matches()) {
				int endLine = string.group(4) == null ? i + 1 : blockEnd(lines, i);
				DeclaredDependency dependency = fromCoordinates(string.group(1), string.group(3), buildFile, i + 1,
						endLine);
				if (dependency != null) {
					dependencies.add(dependency);
				}
				continue;
			}
			Matcher map = MAP_NOTATION.matcher(line);
			if (map.matches() && !map.group(3).contains("$") && !map.group(5).contains("$")) {
				dependencies.add(new DeclaredDependency(map.group(3), map.group(5), scopeOf(map.group(1)), "", "",
						buildFile, i + 1, i + 1, blockEnd(lines, i)));
			}
		}
		return dependencies;
	}

	// One-based line on which the braces opened on line `from` close again; the line
	// itself if none are left open, so a dangling block never yields a partial deletion
	// span.
	private static int blockEnd(String[] lines, int from) {
		int depth = 0;
		for (int i = from; i < lines.length; i++) {
			for (int c = 0; c < lines[i].length(); c++) {
				char ch = lines[i].charAt(c);
				if (ch == '{') {
					depth++;
				}
				else if (ch == '}') {
					depth--;
				}
			}
			if (depth <= 0) {
				return i + 1;
			}
		}
		return from + 1;
	}

	private static DeclaredDependency fromCoordinates(String configuration, String notation, String buildFile, int line,
			int endLine) {
		if (notation.contains("$") || notation.contains("@")) {
			return null;
		}
		String[] parts = notation.split(":", -1);
		if (parts.length < 2 || parts.length > 4 || parts[0].isEmpty() || parts[1].isEmpty()) {
			return null;
		}
		String classifier = parts.length == 4 ? parts[3] : "";
		return new DeclaredDependency(parts[0], parts[1], scopeOf(configuration), "", classifier, buildFile, line, line,
				endLine);
	}

	private static String scopeOf(String configuration) {
		return configuration.startsWith("compileOnly") ? "provided" : "compile";
	}

	// Comments become spaces so line numbers survive; string literals are left intact.
	static String stripComments(String script) {
		StringBuilder out = new StringBuilder(script.length());
		int i = 0;
		while (i < script.length()) {
			char c = script.charAt(i);
			if (c == '"' || c == '\'') {
				int end = literalEnd(script, i);
				out.append(script, i, end);
				i = end;
			}
			else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
				while (i < script.length() && script.charAt(i) != '\n' && script.charAt(i) != '\r') {
					out.append(' ');
					i++;
				}
			}
			else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
				int close = script.indexOf("*/", i + 2);
				int end = close < 0 ? script.length() : close + 2;
				for (; i < end; i++) {
					char inner = script.charAt(i);
					out.append(inner == '\n' || inner == '\r' ? inner : ' ');
				}
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static int literalEnd(String script, int start) {
		char quote = script.charAt(start);
		boolean triple = script.startsWith(String.valueOf(quote).repeat(3), start);
		int i = start + (triple ? 3 : 1);
		while (i < script.length()) {
			char c = script.charAt(i);
			if (c == '\\') {
				i += 2;
			}
			else if (triple && script.startsWith(String.valueOf(quote).repeat(3), i)) {
				return i + 3;
			}
			else if (!triple && c == quote) {
				return i + 1;
			}
			else if (!triple && (c == '\n' || c == '\r')) {
				return i;
			}
			else {
				i++;
			}
		}
		return script.length();
	}

}
