package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.KeptSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardPipelineTest {

	private static final String MAIN = "src/main/java/com/example/";

	@TempDir
	Path root;

	@Test
	void builtInGuardsAreOrderedMostSpecificFirstWithUniqueIds() {
		assertEquals(
				List.of("entry-point", "annotation", "nested-type", "serialization", "native", "annotated-owner",
						"reflective-serialization", "javadoc", "module-info", "literal"),
				ConservativeUnusedCodeAnalyzer.builtInGuards().stream().map(Guard::id).toList());
	}

	@Test
	void theDefaultAnalyzerAppendsGuardsRegisteredThroughServiceLoaderAfterTheBuiltIns() {
		var ids = new ConservativeUnusedCodeAnalyzer().guards().stream().map(Guard::id).toList();

		assertEquals(ConservativeUnusedCodeAnalyzer.builtInGuards().size() + 1, ids.size());
		assertEquals("plugged", ids.get(ids.size() - 1));
		assertEquals(List.of("plugged"),
				ConservativeUnusedCodeAnalyzer.discoveredGuards().stream().map(Guard::id).toList());
	}

	@Test
	void aPluggedGuardKeepsItsCandidatesAndIsNamedInTheExplanation() throws Exception {
		write(MAIN + "App.java", """
				package com.example;

				public class App {
				    private void keptByPlugin() { }
				    private void orphan() { }
				}
				""");

		var report = new ConservativeUnusedCodeAnalyzer().analyze(AnalysisConfig.defaultFor(root).withExplain(true));

		assertEquals(List.of("com.example.App#orphan"), report.issues().stream().map(AnalysisIssue::symbol).toList());
		assertEquals(
				List.of(new KeptSymbol(IssueType.UNUSED_METHOD, "com.example.App#keptByPlugin",
						"src/main/java/com/example/App.java:4:18", "plugged",
						"Private method keptByPlugin is kept: the test plug-in keeps members named keptByPlugin")),
				report.kept());
	}

	@Test
	void anAnalyzerBuiltFromAnExplicitGuardListUsesExactlyThoseGuards() throws Exception {
		write(MAIN + "App.java", """
				package com.example;

				public class App {
				    @SuppressWarnings("unused")
				    private void annotated() { }
				    private void keptByPlugin() { }
				}
				""");

		var withoutGuards = new ConservativeUnusedCodeAnalyzer(List.of()).analyze(AnalysisConfig.defaultFor(root));
		var withOnlyAnnotation = new ConservativeUnusedCodeAnalyzer(List.of(new BuiltInGuards.Annotation()))
			.analyze(AnalysisConfig.defaultFor(root));

		assertEquals(List.of("com.example.App#annotated", "com.example.App#keptByPlugin"),
				withoutGuards.issues().stream().map(AnalysisIssue::symbol).toList());
		assertEquals(List.of("com.example.App#keptByPlugin"),
				withOnlyAnnotation.issues().stream().map(AnalysisIssue::symbol).toList());
	}

	@Test
	void guardsAreOnlyConsultedForCandidatesNoIdentifierReferences() throws Exception {
		write(MAIN + "App.java", """
				package com.example;

				public class App {
				    private void used() { }
				    public void run() { used(); }
				}
				""");
		var guard = new Guard() {
			@Override
			public String id() {
				return "counting";
			}

			@Override
			public Optional<String> keep(Candidate candidate) {
				throw new AssertionError("a referenced member must not reach the guards: " + candidate.symbol());
			}
		};

		var report = new ConservativeUnusedCodeAnalyzer(List.of(guard))
			.analyze(AnalysisConfig.defaultFor(root).withExplain(true));

		assertEquals(List.of(), report.issues());
		assertEquals(List.of(), report.kept());
	}

	@Test
	void duplicateGuardIdsAreRejected() {
		var duplicate = new BuiltInGuards.Annotation();

		var failure = assertThrows(IllegalStateException.class,
				() -> new ConservativeUnusedCodeAnalyzer(List.of(new BuiltInGuards.Annotation(), duplicate)));

		assertEquals("Duplicate guard id: annotation", failure.getMessage());
	}

	private void write(String relativePath, String content) throws IOException {
		var file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}

}
