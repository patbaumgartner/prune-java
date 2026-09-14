package com.patbaumgartner.prune.cli;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.project.GlobMatcher;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class PruneCliApplication {

	static final int EXIT_OK = 0;
	static final int EXIT_ISSUES = 1;
	static final int EXIT_USAGE = 2;
	static final int EXIT_FAILURE = 3;

	private static final String USAGE = "Usage: prune-java <check|fix|baseline> [--root=<dir>] [--exclude=<glob>]... [--baseline=<file>]"
			+ " [--no-test-references] [--explain] [--ci] [--format=terminal|github|json|sarif]";

	private final UnusedCodeAnalyzer analyzer;

	private final ReportRenderer renderer;

	private final Function<Path, AutofixEngine> engines;

	public PruneCliApplication() {
		this(new ConservativeUnusedCodeAnalyzer(), new ReportRenderer(), SourceAutofixEngine::new);
	}

	PruneCliApplication(UnusedCodeAnalyzer analyzer, ReportRenderer renderer) {
		this(analyzer, renderer, SourceAutofixEngine::new);
	}

	PruneCliApplication(UnusedCodeAnalyzer analyzer, ReportRenderer renderer, Function<Path, AutofixEngine> engines) {
		this.analyzer = analyzer;
		this.renderer = renderer;
		this.engines = engines;
	}

	public static void main(String[] args) {
		int exitCode = new PruneCliApplication().run(args, System.out, System.err);
		System.exit(exitCode);
	}

	int run(String[] args, PrintStream out, PrintStream err) {
		if (args.length == 0 || "help".equals(asciiLowerCase(args[0])) || "--help".equals(args[0])) {
			out.println(USAGE);
			return EXIT_OK;
		}

		Command command = Command.from(args[0]);
		if (command == null) {
			err.println("Unknown command: " + args[0]);
			return EXIT_USAGE;
		}

		boolean ciMode = false;
		boolean explain = false;
		boolean testReferences = true;
		String formatOption = null;
		Path root = Path.of(".");
		Path baseline = null;
		List<String> excludes = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			String arg = args[i];
			if ("--ci".equals(arg)) {
				ciMode = true;
			}
			else if ("--explain".equals(arg)) {
				explain = true;
			}
			else if ("--no-test-references".equals(arg)) {
				testReferences = false;
			}
			else if (arg.startsWith("--baseline=")) {
				String value = arg.substring("--baseline=".length());
				if (value.isBlank()) {
					err.println("Option --baseline needs a file path");
					return EXIT_USAGE;
				}
				try {
					baseline = Path.of(value);
				}
				catch (InvalidPathException invalid) {
					err.println("Invalid baseline path: " + value);
					return EXIT_USAGE;
				}
			}
			else if (arg.startsWith("--format=")) {
				formatOption = arg.substring("--format=".length());
			}
			else if (arg.startsWith("--exclude=")) {
				String value = arg.substring("--exclude=".length());
				if (value.isBlank()) {
					err.println("Option --exclude needs a glob pattern");
					return EXIT_USAGE;
				}
				try {
					GlobMatcher.compile(value);
				}
				catch (IllegalArgumentException invalid) {
					err.println(invalid.getMessage());
					return EXIT_USAGE;
				}
				excludes.add(value);
			}
			else if (arg.startsWith("--root=")) {
				String value = arg.substring("--root=".length());
				try {
					root = Path.of(value);
				}
				catch (InvalidPathException invalid) {
					err.println("Invalid project root: " + value);
					return EXIT_USAGE;
				}
				if (!Files.isDirectory(root)) {
					err.println("Project root is not a directory: " + value);
					return EXIT_USAGE;
				}
			}
			else {
				err.println("Unknown option: " + arg);
				return EXIT_USAGE;
			}
		}

		OutputFormat format;
		try {
			format = formatOption != null ? OutputFormat.parse(formatOption)
					: ciMode ? OutputFormat.GITHUB_ANNOTATION : OutputFormat.TERMINAL;
		}
		catch (IllegalArgumentException exception) {
			err.println(exception.getMessage());
			return EXIT_USAGE;
		}

		AnalysisConfig config = AnalysisConfig.defaultFor(root)
			.withExcludePatterns(excludes)
			.withIncludeTestReferences(testReferences)
			.withExplain(explain);
		if (baseline != null) {
			config = config.withBaseline(baseline);
		}
		// The baseline command records every current finding, so the existing baseline
		// must not hide any.
		Path baselineFile = config.baseline().orElseThrow();
		if (command == Command.BASELINE) {
			config = config.withoutBaseline();
		}

		try {
			AnalysisReport report = analyzer.analyze(config);
			if (command == Command.BASELINE) {
				Baseline.write(baselineFile, report.issues());
				out.println("Wrote " + report.issueCount() + " finding(s) to " + baselineFile);
				return EXIT_OK;
			}
			if (command == Command.FIX) {
				report = engines.apply(config.projectRoot()).fix(report);
			}
			out.println(renderer.render(report, format));
			return report.issueCount() > 0 ? EXIT_ISSUES : EXIT_OK;
		}
		catch (UncheckedIOException | IOException | IllegalStateException | IllegalArgumentException exception) {
			err.println("prune-java failed: " + exception.getMessage());
			return EXIT_FAILURE;
		}
	}

	// Only ASCII letters fold, so neither the default locale nor a Kelvin sign or a
	// dotless i can spell a command.
	private static String asciiLowerCase(String value) {
		StringBuilder folded = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
		}
		return folded.toString();
	}

	enum Command {

		CHECK, FIX, BASELINE;

		static @Nullable Command from(String value) {
			return switch (asciiLowerCase(value)) {
				case "check" -> CHECK;
				case "fix" -> FIX;
				case "baseline" -> BASELINE;
				default -> null;
			};
		}

	}

}
