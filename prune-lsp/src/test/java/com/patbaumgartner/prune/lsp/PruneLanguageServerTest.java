package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneLanguageServerTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeAdvertisesSaveOnlySyncAndServerName() throws ExecutionException, InterruptedException {
        var server = connected(new RecordingClient(), config -> emptyReport());

        var result = server.initialize(paramsFor(tempDir)).get();

        var sync = result.getCapabilities().getTextDocumentSync().getRight();
        assertEquals(Boolean.FALSE, sync.getOpenClose());
        assertEquals(TextDocumentSyncKind.None, sync.getChange());
        assertEquals(Boolean.TRUE, sync.getSave().getLeft());
        assertEquals("prune-java", result.getServerInfo().getName());
    }

    @Test
    void initializedRunsAnalyzerAgainstWorkspaceFolderAndLogsSummary() {
        var client = new RecordingClient();
        var seenRoots = new ArrayList<Path>();
        var server = connected(client, config -> {
            seenRoots.add(config.projectRoot());
            return new AnalysisReport(List.of(), "scaffold summary", true);
        });
        server.initialize(paramsFor(tempDir));

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
        server.initialize(params);

        server.initialized(new InitializedParams());

        assertEquals(List.of(tempDir), seenRoots);
    }

    @Test
    void publishesOneDiagnosticPerIssueUri() {
        var client = new RecordingClient();
        var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo",
                "src/main/java/Foo.java:4", "Class Foo is unused", false);
        var server = connected(client, config -> new AnalysisReport(List.of(issue), "one", true));
        server.initialize(paramsFor(tempDir));

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
        var issue = new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, "Foo",
                "src/main/java/Foo.java", "unused", false);
        Deque<AnalysisReport> reports = new ArrayDeque<>(List.of(
                new AnalysisReport(List.of(issue), "first", true),
                emptyReport()));
        var server = connected(client, config -> reports.pop());
        server.initialize(paramsFor(tempDir));
        server.initialized(new InitializedParams());

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
        server.initialize(paramsFor(tempDir));
        server.initialized(new InitializedParams());

        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));

        assertEquals(2, client.logged.size());
    }

    @Test
    void exitAfterShutdownSignalsExitCodeZero() throws ExecutionException, InterruptedException {
        var server = connected(new RecordingClient(), config -> emptyReport());

        var shutdownResult = server.shutdown().get();
        assertFalse(server.exited().isDone());
        server.exit();

        assertNull(shutdownResult);
        assertEquals(0, server.exited().get());
    }

    @Test
    void exitWithoutShutdownSignalsExitCodeOne() throws ExecutionException, InterruptedException {
        var server = connected(new RecordingClient(), config -> emptyReport());

        server.exit();

        assertEquals(1, server.exited().get());
    }

    private static PruneLanguageServer connected(LanguageClient client, UnusedCodeAnalyzer analyzer) {
        var server = new PruneLanguageServer(analyzer);
        server.connect(client);
        return server;
    }

    private static InitializeParams paramsFor(Path root) {
        var params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "root")));
        return params;
    }

    private static AnalysisReport emptyReport() {
        return new AnalysisReport(List.of(), "empty", true);
    }

    static final class RecordingClient implements LanguageClient {

        final List<PublishDiagnosticsParams> published = new ArrayList<>();
        final List<MessageParams> logged = new ArrayList<>();

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
