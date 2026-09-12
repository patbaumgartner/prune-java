package com.patbaumgartner.prune.core.config;

import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.IssueType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Findings a project has accepted, one "TYPE symbol" per line. Matching ignores the
// location so a moved line does not resurrect an accepted finding; the type keeps a
// renamed rule from matching.
public final class Baseline {

	private static final String HEADER = """
			# prune-java baseline: findings this project accepts, one "TYPE symbol" per line.
			# check reports only findings that are not listed here; fix never touches a listed symbol.
			# Regenerate with the baseline command, goal, or task after reviewing the remaining findings.
			""";

	private final Set<String> entries;

	private Baseline(Set<String> entries) {
		this.entries = Set.copyOf(entries);
	}

	public static Baseline empty() {
		return new Baseline(Set.of());
	}

	// A missing file is an empty baseline: the file appears once the first findings are
	// accepted.
	public static Baseline load(Path file) {
		if (!Files.isRegularFile(file)) {
			return empty();
		}
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Cannot read baseline " + file, exception);
		}
	}

	public static Baseline parse(String content, String source) {
		Set<String> entries = new LinkedHashSet<>();
		int lineNumber = 0;
		for (String raw : content.split("\\R", -1)) {
			lineNumber++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] columns = line.split("\\s+");
			if (columns.length != 2) {
				throw new IllegalArgumentException(
						source + ":" + lineNumber + ": expected \"TYPE symbol\" but found \"" + line + "\"");
			}
			try {
				IssueType.valueOf(columns[0]);
			}
			catch (IllegalArgumentException unknownType) {
				throw new IllegalArgumentException(source + ":" + lineNumber + ": unknown issue type " + columns[0]);
			}
			entries.add(key(columns[0], columns[1]));
		}
		return new Baseline(entries);
	}

	public static String render(List<AnalysisIssue> issues) {
		List<String> lines = new ArrayList<>();
		for (AnalysisIssue issue : issues) {
			lines.add(key(issue.type().name(), issue.symbol()));
		}
		lines.sort(Comparator.naturalOrder());
		StringBuilder rendered = new StringBuilder(HEADER);
		for (String line : new LinkedHashSet<>(lines)) {
			rendered.append(line).append('\n');
		}
		return rendered.toString();
	}

	public static void write(Path file, List<AnalysisIssue> issues) throws IOException {
		Files.writeString(file, render(issues), StandardCharsets.UTF_8);
	}

	public boolean contains(AnalysisIssue issue) {
		return entries.contains(key(issue.type().name(), issue.symbol()));
	}

	public int size() {
		return entries.size();
	}

	private static String key(String type, String symbol) {
		return type + " " + symbol;
	}

}
