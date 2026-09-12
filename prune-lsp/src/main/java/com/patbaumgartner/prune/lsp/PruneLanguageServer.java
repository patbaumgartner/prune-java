package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class PruneLanguageServer implements LanguageServer, LanguageClientAware {

    private final UnusedCodeAnalyzer analyzer;
    private final DiagnosticMapper mapper = new DiagnosticMapper();
    private final Set<String> publishedUris = new HashSet<>();
    private final CompletableFuture<Integer> exited = new CompletableFuture<>();

    private LanguageClient client;
    private Path projectRoot = Path.of(".").toAbsolutePath().normalize();
    private volatile boolean shutdownRequested;

    public PruneLanguageServer() {
        this(new ConservativeUnusedCodeAnalyzer());
    }

    PruneLanguageServer(UnusedCodeAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        projectRoot = resolveProjectRoot(params);

        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(false);
        sync.setChange(TextDocumentSyncKind.None);
        sync.setSave(Boolean.TRUE);

        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(Either.forRight(sync));

        return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo(DiagnosticMapper.SOURCE)));
    }

    @Override
    public void initialized(InitializedParams params) {
        refreshDiagnostics();
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        exited.complete(shutdownRequested ? 0 : 1);
    }

    // Exit code per LSP spec: 0 after shutdown, 1 if exit arrives without it.
    public CompletableFuture<Integer> exited() {
        return exited;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                refreshDiagnostics();
            }
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {
            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
                refreshDiagnostics();
            }
        };
    }

    synchronized void refreshDiagnostics() {
        AnalysisReport report = analyzer.analyze(AnalysisConfig.defaultFor(projectRoot));
        Map<String, List<Diagnostic>> byUri = mapper.map(report, projectRoot);
        client.logMessage(new MessageParams(MessageType.Info, report.summary()));

        for (String stale : publishedUris) {
            if (!byUri.containsKey(stale)) {
                client.publishDiagnostics(new PublishDiagnosticsParams(stale, List.of()));
            }
        }
        byUri.forEach((uri, diagnostics) -> client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics)));

        publishedUris.clear();
        publishedUris.addAll(byUri.keySet());
    }

    private static Path resolveProjectRoot(InitializeParams params) {
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        String uri = folders != null && !folders.isEmpty() ? folders.get(0).getUri() : params.getRootUri();
        if (uri == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        try {
            return Path.of(URI.create(uri)).normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException nonLocalUri) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }
}
