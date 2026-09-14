package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.FixPlan;
import com.patbaumgartner.prune.core.fix.FixResult;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneLanguageServerTest {

	@TempDir
	Path tempDir;

	@Test
	void initializeAdvertisesSaveOnlySyncAndServerName() throws InterruptedException, ExecutionException {
		var server = connected(new RecordingClient(), config -> emptyReport());

		var result = server.initialize(paramsFor(tempDir)).get();

		var sync = result.getCapabilities().getTextDocumentSync().getRight();
		assertFalse(sync.getOpenClose());
		assertEquals(TextDocumentSyncKind.None, sync.getChange());
		assertTrue(sync.getSave().getLeft());
		assertEquals("prune-java", result.getServerInfo().getName());
		assertEquals(List.of(CodeActionKind.QuickFix),
				result.getCapabilities().getCodeActionProvider().getRight().getCodeActionKinds());
	}

	@Test
	void initializedRunsAnalyzerAgainstWorkspaceFolderAndLogsSummary() {
		var client = new RecordingClient();
		var seenRoots = new ArrayList<Path>();
		var server = connected(client, config -> {
			seenRoots.add(config.projectRoot());
			return new AnalysisReport(List.of(), "scaffold summary", true);
		});
		server.initialize(paramsFor(tempDir)).join();

		server.initialized(new InitializedParams());

		assertEquals(List.of(tempDir), seenRoots);
		assertTrue(client.published.isEmpty());
		assertEquals(1, client.logged.size());
		assertEquals(MessageType.Info, client.logged.get(0).getType());
		assertEquals("scaffold summary", client.logged.get(0).getMessage());
	}

	@Test
	void initializedFallsBackToRootUriWhenNoWorkspaceFolders() {
		var seenRoots = new ArrayList<Path>();
		var server = connected(new RecordingClient(), config -> {
			seenRoots.add(config.projectRoot());
			return emptyReport();
		});
		var params = new InitializeParams();
		params.setRootUri(tempDir.toUri().toString());
		server.initialize(params).join();

		server.initialized(new InitializedParams());

		assertEquals(List.of(tempDir), seenRoots);
	}

	@Test
	void publishesOneDiagnosticPerIssueUri() {
		var client = new RecordingClient();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo", "src/main/java/Foo.java:4",
				"Class Foo is unused", false);
		var server = connected(client, config -> new AnalysisReport(List.of(issue), "one", true));
		server.initialize(paramsFor(tempDir)).join();

		server.initialized(new InitializedParams());

		assertEquals(1, client.published.size());
		var published = client.published.get(0);
		assertEquals(tempDir.resolve("src/main/java/Foo.java").toUri().toString(), published.getUri());
		assertEquals(1, published.getDiagnostics().size());
		assertEquals("Class Foo is unused", published.getDiagnostics().get(0).getMessage().getLeft());
		assertEquals("UNUSED_CLASS", published.getDiagnostics().get(0).getCode().getLeft());
	}

	@Test
	void clearsDiagnosticsForUrisThatNoLongerHaveIssues() {
		var client = new RecordingClient();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo", "src/main/java/Foo.java",
				"unused", false);
		Deque<AnalysisReport> reports = new ArrayDeque<>(
				List.of(new AnalysisReport(List.of(issue), "first", true), emptyReport()));
		var server = connected(client, config -> reports.pop());
		initialized(server);

		server.getTextDocumentService().didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier("file:///x")));

		assertEquals(2, client.published.size());
		var cleared = client.published.get(1);
		assertEquals(tempDir.resolve("src/main/java/Foo.java").toUri().toString(), cleared.getUri());
		assertTrue(cleared.getDiagnostics().isEmpty());
	}

	@Test
	void watchedFileChangesTriggerReanalysis() {
		var client = new RecordingClient();
		var server = connected(client, config -> emptyReport());
		initialized(server);

		server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));

		assertEquals(2, client.logged.size());
	}

	@Test
	void registersJavaAndBuildFileWatchersWhenTheClientSupportsDynamicRegistration() {
		var client = new RecordingClient();
		var server = connected(client, config -> emptyReport());
		var params = paramsFor(tempDir);
		var watched = new DidChangeWatchedFilesCapabilities(true);
		var workspace = new WorkspaceClientCapabilities();
		workspace.setDidChangeWatchedFiles(watched);
		params.setCapabilities(new ClientCapabilities(workspace, null, null));
		server.initialize(params).join();

		server.initialized(new InitializedParams());

		assertEquals(1, client.registered.size());
		var registration = client.registered.get(0).getRegistrations().get(0);
		assertEquals("workspace/didChangeWatchedFiles", registration.getMethod());
		var options = (DidChangeWatchedFilesRegistrationOptions) registration.getRegisterOptions();
		assertEquals(PruneLanguageServer.WATCHED_GLOBS,
				options.getWatchers().stream().map(watcher -> watcher.getGlobPattern().getLeft()).toList());
		assertEquals(1, client.logged.size());
	}

	@Test
	void doesNotRegisterWatchersForClientsWithoutDynamicRegistration() {
		var client = new RecordingClient();
		var server = connected(client, config -> emptyReport());
		server.initialize(paramsFor(tempDir)).join();

		server.initialized(new InitializedParams());

		assertTrue(client.registered.isEmpty());
	}

	@Test
	void analysisFailuresAreLoggedAsErrorsAndKeepEarlierDiagnostics() {
		var client = new RecordingClient();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo", "src/main/java/Foo.java:1",
				"unused", false);
		Deque<AnalysisReport> reports = new ArrayDeque<>(List.of(new AnalysisReport(List.of(issue), "first", true)));
		var server = connected(client, config -> {
			if (reports.isEmpty()) {
				throw new UncheckedIOException(new IOException("disk on fire"));
			}
			return reports.pop();
		});
		initialized(server);

		server.getTextDocumentService().didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier("file:///x")));

		assertEquals(1, client.published.size());
		assertEquals(MessageType.Error, client.logged.get(1).getType());
		assertTrue(client.logged.get(1).getMessage().contains("disk on fire"), client.logged.get(1).getMessage());
	}

	@Test
	void notificationsAfterShutdownDoNotAnalyzeOrPublishDiagnostics() {
		var client = new RecordingClient();
		var seenRoots = new ArrayList<Path>();
		var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo", "Foo.java", "unused", false);
		var server = connected(client, config -> {
			seenRoots.add(config.projectRoot());
			return new AnalysisReport(List.of(issue), "one", true);
		});
		initialized(server);
		server.shutdown().join();

		server.getTextDocumentService()
			.didSave(new DidSaveTextDocumentParams(
					new TextDocumentIdentifier(tempDir.resolve("Foo.java").toUri().toString())));
		server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));
		server.initialized(new InitializedParams());

		assertEquals(List.of(tempDir), seenRoots);
		assertEquals(1, client.logged.size());
		assertEquals(1, client.published.size());
	}

	@Test
	void exitAfterShutdownSignalsExitCodeZero() throws InterruptedException, ExecutionException {
		var server = connected(new RecordingClient(), config -> emptyReport());

		var shutdownResult = server.shutdown().get();
		assertFalse(server.exited().isDone());
		server.exit();

		assertNull(shutdownResult);
		assertEquals(0, server.exited().get());
	}

	@Test
	void exitWithoutShutdownSignalsExitCodeOne() throws InterruptedException, ExecutionException {
		var server = connected(new RecordingClient(), config -> emptyReport());

		server.exit();

		assertEquals(1, server.exited().get());
	}

	@Test
	void codeActionOffersOneQuickFixPerAutofixableDiagnosticWithEveryEngineEdit()
			throws IOException, InterruptedException, ExecutionException {
		var client = new RecordingClient();
		var source = Files.createDirectories(tempDir.resolve("src/main/java")).resolve("Foo.java");
		Files.writeString(source,
				"import java.util.Set;\n\nclass Foo {\n    private Set<String> x;\n    void y() { }\n}\n",
				StandardCharsets.UTF_8);
		var fixable = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "Foo#x",
				"src/main/java/Foo.java:4:25", "Private field x is never used", true);
		var manual = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo", "src/main/java/Foo.java:3:7",
				"Class Foo is never referenced", false);
		var server = connected(client, config -> new AnalysisReport(List.of(fixable, manual), "two", true),
				root -> new SourceAutofixEngine(root));
		initialized(server);
		var published = client.published.get(0);

		var actions = codeActionsFor(server, published.getUri(), published.getDiagnostics());

		assertEquals(1, actions.size());
		var action = actions.get(0).getRight();
		assertEquals("Remove unused private field x", action.getTitle());
		assertEquals(CodeActionKind.QuickFix, action.getKind());
		assertEquals(List.of(published.getDiagnostics().get(0)), action.getDiagnostics());
		var edits = action.getEdit().getChanges().get(source.toUri().toString());
		assertNotNull(edits);
		assertEquals(2, edits.size());
		assertEquals(new Range(new Position(0, 0), new Position(2, 0)), edits.get(0).getRange());
		assertEquals(new Range(new Position(3, 0), new Position(4, 0)), edits.get(1).getRange());
		assertTrue(edits.stream().allMatch(edit -> edit.getNewText().isEmpty()));
	}

	@Test
	void codeActionIgnoresForeignDiagnosticsAndUnknownIssues() throws InterruptedException, ExecutionException {
		var client = new RecordingClient();
		var engine = new CountingEngine();
		var server = initializedWithOneFixableField(client, engine);
		var uri = client.published.get(0).getUri();
		var foreign = new Diagnostic(fullRange(), "Private field x is never used");
		foreign.setSource("checkstyle");
		foreign.setCode("UNUSED_FIELD");
		var stale = new Diagnostic(fullRange(), "Private field gone is never used");
		stale.setSource("prune-java");
		stale.setCode("UNUSED_FIELD");

		var actions = codeActionsFor(server, uri, List.of(foreign, stale));
		var none = codeActionsFor(server, uri, List.of());

		assertTrue(actions.isEmpty());
		assertTrue(none.isEmpty());
		assertEquals(0, engine.plans);
	}

	@Test
	void codeActionUsesTheDiagnosticLineToTellSameNamedMembersApart()
			throws IOException, InterruptedException, ExecutionException {
		var client = new RecordingClient();
		var source = Files.createDirectories(tempDir.resolve("src/main/java")).resolve("Outer.java");
		Files.writeString(source,
				"class Outer {\n    static class A {\n        private int x;\n    }\n    static class B {\n        private int x;\n    }\n}\n",
				StandardCharsets.UTF_8);
		var first = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "Outer.A#x",
				"src/main/java/Outer.java:3:21", "Private field x is never used", true);
		var second = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "Outer.B#x",
				"src/main/java/Outer.java:6:21", "Private field x is never used", true);
		var server = connected(client, config -> new AnalysisReport(List.of(first, second), "two", true),
				root -> new SourceAutofixEngine(root));
		initialized(server);
		var published = client.published.get(0);
		var secondDiagnostic = published.getDiagnostics().get(1);

		var actions = codeActionsFor(server, published.getUri(), List.of(secondDiagnostic));

		assertEquals(1, actions.size());
		var edits = actions.get(0).getRight().getEdit().getChanges().get(source.toUri().toString());
		assertNotNull(edits);
		assertEquals(List.of(new Range(new Position(5, 0), new Position(6, 0))),
				edits.stream().map(edit -> edit.getRange()).toList());
	}

	@Test
	void codeActionOffersNothingWhenTheEnginePlansNothing() throws InterruptedException, ExecutionException {
		var client = new RecordingClient();
		var engine = new CountingEngine();
		var server = initializedWithOneFixableField(client, engine);
		var published = client.published.get(0);

		var actions = codeActionsFor(server, published.getUri(), published.getDiagnostics());

		assertTrue(actions.isEmpty());
		assertEquals(1, engine.plans);
	}

	private static PruneLanguageServer connected(LanguageClient client, UnusedCodeAnalyzer analyzer) {
		return connected(client, analyzer, root -> new CountingEngine());
	}

	private static PruneLanguageServer connected(LanguageClient client, UnusedCodeAnalyzer analyzer,
			Function<Path, AutofixEngine> engines) {
		var server = new PruneLanguageServer(analyzer, engines);
		server.connect(client);
		return server;
	}

	private PruneLanguageServer initializedWithOneFixableField(RecordingClient client, CountingEngine engine) {
		var issue = new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING, "Foo#x", "src/main/java/Foo.java:2:17",
				"Private field x is never used", true);
		var server = connected(client, config -> new AnalysisReport(List.of(issue), "one", true), root -> engine);
		initialized(server);
		return server;
	}

	private void initialized(PruneLanguageServer server) {
		server.initialize(paramsFor(tempDir)).join();
		server.initialized(new InitializedParams());
	}

	private static List<Either<Command, CodeAction>> codeActionsFor(PruneLanguageServer server, String uri,
			List<Diagnostic> diagnostics) throws InterruptedException, ExecutionException {
		return server.getTextDocumentService()
			.codeAction(new CodeActionParams(new TextDocumentIdentifier(uri), fullRange(),
					new CodeActionContext(diagnostics)))
			.get();
	}

	private static Range fullRange() {
		return new Range(new Position(0, 0), new Position(1000, 0));
	}

	private static InitializeParams paramsFor(Path root) {
		var params = new InitializeParams();
		params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "root")));
		return params;
	}

	private static AnalysisReport emptyReport() {
		return new AnalysisReport(List.of(), "empty", true);
	}

	static class CountingEngine implements AutofixEngine {

		int plans;

		@Override
		public FixPlan plan(AnalysisReport report) {
			plans++;
			return FixPlan.empty();
		}

		@Override
		public FixResult apply(FixPlan plan) {
			return FixResult.nothing();
		}

	}

	static final class RecordingClient implements LanguageClient {

		final List<PublishDiagnosticsParams> published = new ArrayList<>();

		final List<MessageParams> logged = new ArrayList<>();

		final List<RegistrationParams> registered = new ArrayList<>();

		@Override
		public CompletableFuture<Void> registerCapability(RegistrationParams params) {
			registered.add(params);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void telemetryEvent(Object object) {
		}

		@Override
		public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
			published.add(diagnostics);
		}

		@Override
		public void showMessage(MessageParams messageParams) {
		}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void logMessage(MessageParams message) {
			logged.add(message);
		}

	}

}
