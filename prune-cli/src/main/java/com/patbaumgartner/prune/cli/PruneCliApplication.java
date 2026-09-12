package com.patbaumgartner.prune.cli;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.OutputFormat;
import com.patbaumgartner.prune.core.report.ReportRenderer;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public final class PruneCliApplication {

    private final UnusedCodeAnalyzer analyzer;
    private final ReportRenderer renderer;

    public PruneCliApplication() {
        this(new ConservativeUnusedCodeAnalyzer(), new ReportRenderer());
    }

    PruneCliApplication(UnusedCodeAnalyzer analyzer, ReportRenderer renderer) {
        this.analyzer = analyzer;
        this.renderer = renderer;
    }

    public static void main(String[] args) {
        int exitCode = new PruneCliApplication().run(args, System.out, System.err);
        System.exit(exitCode);
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            out.println("Usage: prune-java <check|fix> [--ci] [--format=terminal|github|json]");
            return 0;
        }

        Command command = Command.from(args[0]);
        if (command == null) {
            err.println("Unknown command: " + args[0]);
            return 2;
        }

        for (int i = 1; i < args.length; i++) {
            if (!"--ci".equals(args[i]) && !args[i].startsWith("--format=")) {
                err.println("Unknown option: " + args[i]);
                return 2;
            }
        }

        OutputFormat format;
        try {
            format = parseFormat(args);
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            return 2;
        }

        boolean ciMode = Arrays.asList(args).contains("--ci");
        AnalysisConfig defaults = AnalysisConfig.defaultFor(Path.of("."));

        AnalysisConfig config = new AnalysisConfig(
                defaults.projectRoot(),
                defaults.includePatterns(),
                defaults.excludePatterns(),
                ciMode,
                defaults.includeTestReferences(),
                command == Command.CHECK
        );

        AnalysisReport report = analyzer.analyze(config);
        out.println(renderer.render(report, format));

        return switch (command) {
            case CHECK -> report.issueCount() > 0 ? 1 : 0;
            case FIX -> 0;
        };
    }

    private OutputFormat parseFormat(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--format=")) {
                String value = arg.substring("--format=".length());
                return switch (value) {
                    case "terminal" -> OutputFormat.TERMINAL;
                    case "github" -> OutputFormat.GITHUB_ANNOTATION;
                    case "json" -> OutputFormat.JSON;
                    default -> throw new IllegalArgumentException("Unsupported format: " + value);
                };
            }
        }

        return Arrays.asList(args).contains("--ci") ? OutputFormat.GITHUB_ANNOTATION : OutputFormat.TERMINAL;
    }

    enum Command {
        CHECK,
        FIX;

        static Command from(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "check" -> CHECK;
                case "fix" -> FIX;
                default -> null;
            };
        }
    }
}
