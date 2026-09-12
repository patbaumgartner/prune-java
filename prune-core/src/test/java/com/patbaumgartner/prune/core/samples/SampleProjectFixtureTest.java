package com.patbaumgartner.prune.core.samples;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.analyzer.Guard;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.dependency.MavenPomParser;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.KeptSymbol;
import com.patbaumgartner.prune.core.report.Severity;
import com.patbaumgartner.prune.core.report.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleProjectFixtureTest {

	private static final List<String> SAMPLES = discoverSamples();

	private static final Set<String> DEPENDENCY_GUARDS = Set.of("dependency-scope", "dependency-type", "runtime-only",
			"ambiguous-coordinates");

	@Test
	void everySampleIsDiscoveredAndDeclaresBothUnusedAndKeptGroundTruth() {
		assertEquals(List.of("gradle-sample", "helidon-sample", "maven-sample", "micronaut-sample", "quarkus-sample",
				"spring-boot-sample"), SAMPLES);
		for (var sample : SAMPLES) {
			assertFalse(symbols(sample, "unused").isEmpty(), sample);
			assertFalse(symbols(sample, "kept").isEmpty(), sample);
		}
	}

	@Test
	void everyGroundTruthLineNamesAKnownVerdictIssueTypeOrGuard() {
		var guards = Stream
			.concat(ConservativeUnusedCodeAnalyzer.builtInGuards().stream().map(Guard::id), DEPENDENCY_GUARDS.stream())
			.collect(Collectors.toSet());

		for (var sample : SAMPLES) {
			for (var columns : entries(sample)) {
				assertEquals(3, columns.length, sample + ": " + String.join(" ", columns));

				switch (columns[0]) {
					case "unused", "test-only" -> IssueType.valueOf(columns[1]);
					case "kept" -> assertTrue("ANY".equals(columns[1]) || guards.contains(columns[1]),
							sample + ": unknown guard " + columns[1] + " for " + columns[2]);
					default -> throw new AssertionError(sample + ": unknown verdict " + columns[0]);
				}
			}
		}
	}

	@Test
	void analyzerReportsExactlyTheUnusedRowsOfEverySample() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample));

			assertEquals(findings(sample, "unused"), findings(report), sample);
			assertTrue(report.summary().contains(sample), report.summary());
			assertTrue(report.summary().contains(report.issueCount() + " issue(s)"), report.summary());
		}
	}

	@Test
	void analyzerNeverReportsASymbolASampleMarksAsKept() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample));

			assertEquals(List.of(), keptSymbolsReportedIn(report, symbols(sample, "kept")), sample);
		}
	}

	@Test
	void explanationsNameExactlyTheGuardsTheManifestsRecord() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample).withExplain(true));

			var explained = report.kept()
				.stream()
				.collect(Collectors.toMap(KeptSymbol::symbol, KeptSymbol::guard, (a, b) -> a + "," + b));
			var recorded = entries(sample).stream()
				.filter(columns -> "kept".equals(columns[0]) && !"ANY".equals(columns[1]))
				.collect(Collectors.toMap(columns -> columns[2], columns -> columns[1]));
			assertEquals(recorded, explained, sample + ": every guarded symbol is recorded with its guard, and every "
					+ "recorded guard is what the analyzer reports");
			for (var kept : report.kept()) {
				assertTrue(kept.message().contains(" is kept: "), kept.message());
			}
		}
	}

	@Test
	void symbolsMarkedKeptAnyAreReferencedByIdentifiersAndNeverReachAGuard() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample).withExplain(true));

			var explained = report.kept().stream().map(KeptSymbol::symbol).collect(Collectors.toSet());
			for (var columns : entries(sample)) {
				if ("kept".equals(columns[0]) && "ANY".equals(columns[1])) {
					assertFalse(explained.contains(columns[2]), sample + ": " + columns[2] + " reached a guard");
				}
			}
		}
	}

	@Test
	void excludingTestReferencesReportsExactlyTheTestOnlyRowsAsWellAndSaysSo() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample).withIncludeTestReferences(false));

			var expected = Stream.concat(findings(sample, "unused").stream(), findings(sample, "test-only").stream())
				.collect(Collectors.toSet());
			assertEquals(expected, findings(report), sample);
			var testOnly = symbols(sample, "test-only");
			for (var issue : report.issues()) {
				assertEquals(testOnly.contains(issue.symbol()),
						issue.message().endsWith(" is only referenced from tests"), sample + ": " + issue.message());
			}
		}
	}

	@Test
	void aBaselineWrittenFromAReportSuppressesEveryFindingOfEverySample(@TempDir Path baselines) throws Exception {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var baseline = baselines.resolve(sample + ".txt");
			var report = analyzer.analyze(config(sample));
			Baseline.write(baseline, report.issues());

			var suppressed = analyzer.analyze(config(sample).withBaseline(baseline).withExplain(true));

			assertEquals(List.of(), suppressed.issues(), sample);
			assertTrue(
					suppressed.summary()
						.endsWith(" " + report.issueCount() + " issue(s) suppressed by " + baseline + "."),
					suppressed.summary());
			var byBaseline = suppressed.kept()
				.stream()
				.filter(kept -> "baseline".equals(kept.guard()))
				.map(kept -> kept.type() + " " + kept.symbol())
				.collect(Collectors.toSet());
			assertEquals(findings(sample, "unused"), byBaseline, sample);
		}
	}

	@Test
	void everyFindingPointsAtALineThatNamesTheSymbol() throws Exception {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var root = sampleRoot(sample);
			var report = analyzer.analyze(config(sample).withIncludeTestReferences(false).withExplain(true));

			for (var issue : report.issues()) {
				assertLineNamesSymbol(root, issue.location(), issue.symbol());
			}
			for (var kept : report.kept()) {
				assertLineNamesSymbol(root, kept.location(), kept.symbol());
			}
		}
	}

	@Test
	void onlyTheUnusedClassFindingsNeedAHuman() {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var report = analyzer.analyze(config(sample));

			for (var issue : report.issues()) {
				assertEquals(issue.type() != IssueType.UNUSED_CLASS, issue.autoFixable(), issue.symbol());
			}
		}
	}

	// Harness coupling lives in the plugin builds; a sample that applied prune-java would
	// no longer resemble the projects it stands in for.
	@Test
	void samplesNeverApplyPruneJavaAndCommitNoBaseline() throws Exception {
		assertFalse(Files.readString(samplesDirectory().resolve("pom.xml")).contains("com.patbaumgartner"));
		for (var sample : SAMPLES) {
			var root = sampleRoot(sample);
			assertFalse(Files.exists(root.resolve(AnalysisConfig.DEFAULT_BASELINE_FILE)), sample);
			try (Stream<Path> files = Files.walk(root)) {
				for (var file : files.filter(Files::isRegularFile)
					.filter(SampleProjectFixtureTest::isSampleSource)
					.toList()) {
					assertFalse(Files.readString(file).contains("com.patbaumgartner"), file.toString());
				}
			}
		}
	}

	// The aggregator makes the Maven samples one reactor, which is also what the analyzer
	// sees:
	// references are collected from all of samples/, so a sample's dead code has to be
	// dead
	// there too. The Gradle samples keep their own settings.gradle and stay separate
	// builds.
	@Test
	void theAggregatorListsExactlyTheMavenSamplesAndIsNotTheirParent() throws Exception {
		var aggregator = MavenPomParser.structure(Files.readString(samplesDirectory().resolve("pom.xml")));

		var mavenSamples = SAMPLES.stream()
			.filter(sample -> Files.isRegularFile(sampleRoot(sample).resolve("pom.xml")));
		assertEquals(mavenSamples.sorted().toList(), aggregator.modules().stream().sorted().toList());
		assertEquals(List.of("gradle-sample", "micronaut-sample"),
				SAMPLES.stream().filter(sample -> !aggregator.modules().contains(sample)).toList());
		for (var module : aggregator.modules()) {
			var sample = MavenPomParser.structure(Files.readString(sampleRoot(module).resolve("pom.xml")));
			assertFalse(aggregator.isParentOf(sample), module);
		}
	}

	// A sample must not lean on its neighbours: copied out of samples/ and analyzed
	// alone, it
	// reports and explains exactly what it does inside the aggregator.
	@Test
	void everySampleReportsTheSameFindingsWhenAnalyzedOutsideTheAggregator(@TempDir Path elsewhere) throws Exception {
		var analyzer = new ConservativeUnusedCodeAnalyzer();

		for (var sample : SAMPLES) {
			var copy = elsewhere.resolve(sample);
			copySample(sampleRoot(sample), copy);
			var inside = analyzer.analyze(config(sample).withIncludeTestReferences(false).withExplain(true));

			var alone = analyzer
				.analyze(AnalysisConfig.defaultFor(copy).withIncludeTestReferences(false).withExplain(true));

			assertEquals(findings(inside), findings(alone), sample);
			assertEquals(explanations(inside), explanations(alone), sample);
		}
	}

	@Test
	void keptSymbolGuardFailsWhenAReportNamesAKeptSymbol() {
		var kept = symbols("maven-sample", "kept");
		var symbol = kept.get(0);
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, symbol, "A.java", "unused", false);

		var reported = keptSymbolsReportedIn(new AnalysisReport(List.of(issue), "synthetic", true), kept);

		assertEquals(List.of(symbol), reported);
	}

	private static void assertLineNamesSymbol(Path root, String location, String symbol) throws IOException {
		var parsed = SourceLocation.parse(location);
		var lines = Files.readAllLines(root.resolve(parsed.path()));
		assertTrue(parsed.hasLine() && parsed.line() <= lines.size(), location);
		assertTrue(lines.get(parsed.line() - 1).contains(simpleName(symbol)), location + " -> " + symbol);
	}

	private static boolean isSampleSource(Path file) {
		var name = file.getFileName().toString();
		var path = file.toString().replace('\\', '/');
		return !"expected-findings.txt".equals(name) && !path.contains("/build/") && !path.contains("/target/")
				&& !path.contains("/.gradle/");
	}

	private static Set<String> explanations(AnalysisReport report) {
		return report.kept().stream().map(kept -> kept.guard() + " " + kept.symbol()).collect(Collectors.toSet());
	}

	private static void copySample(Path source, Path target) throws IOException {
		try (Stream<Path> tree = Files.walk(source)) {
			for (var path : tree.filter(SampleProjectFixtureTest::isSampleSource).toList()) {
				var destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				}
				else {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination);
				}
			}
		}
	}

	private static AnalysisConfig config(String sample) {
		return AnalysisConfig.defaultFor(sampleRoot(sample));
	}

	private static Set<String> findings(String sample, String verdict) {
		return entries(sample).stream()
			.filter(columns -> columns[0].equals(verdict))
			.map(columns -> columns[1] + " " + columns[2])
			.collect(Collectors.toSet());
	}

	private static Set<String> findings(AnalysisReport report) {
		var findings = report.issues()
			.stream()
			.map(issue -> issue.type() + " " + issue.symbol())
			.collect(Collectors.toSet());
		assertEquals(report.issueCount(), findings.size(), "duplicate findings in " + report.issues());
		return findings;
	}

	private static String simpleName(String symbol) {
		if (symbol.contains("#")) {
			return symbol.substring(symbol.indexOf('#') + 1);
		}
		if (symbol.contains(":")) {
			return symbol.substring(symbol.indexOf(':') + 1);
		}
		return symbol.substring(symbol.lastIndexOf('.') + 1);
	}

	private static List<String> keptSymbolsReportedIn(AnalysisReport report, Collection<String> kept) {
		return report.issues().stream().map(AnalysisIssue::symbol).filter(kept::contains).toList();
	}

	private static List<String> symbols(String sample, String verdict) {
		return entries(sample).stream()
			.filter(columns -> columns[0].equals(verdict))
			.map(columns -> columns[2])
			.toList();
	}

	private static List<String[]> entries(String sample) {
		var manifest = sampleRoot(sample).resolve("expected-findings.txt");
		try {
			return Files.readAllLines(manifest)
				.stream()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(line -> line.split("\\s+"))
				.toList();
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Cannot read " + manifest, exception);
		}
	}

	private static List<String> discoverSamples() {
		try (Stream<Path> directories = Files.list(samplesDirectory())) {
			return directories.filter(directory -> Files.isRegularFile(directory.resolve("expected-findings.txt")))
				.map(directory -> directory.getFileName().toString())
				.sorted()
				.toList();
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static Path sampleRoot(String sample) {
		return samplesDirectory().resolve(sample);
	}

	private static Path samplesDirectory() {
		for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
			var candidate = directory.resolve("samples");
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("No samples directory above " + Path.of("").toAbsolutePath());
	}

}
