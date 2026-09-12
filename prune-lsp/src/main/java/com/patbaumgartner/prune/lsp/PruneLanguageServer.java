package com.patbaumgartner.prune.lsp;

import com.patbaumgartner.prune.core.analyzer.ConservativeUnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.analyzer.UnusedCodeAnalyzer;
import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.fix.AutofixEngine;
import com.patbaumgartner.prune.core.fix.SourceAutofixEngine;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
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
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class PruneLanguageServer implements LanguageServer, LanguageClientAware {

	static final List<String> WATCHED_GLOBS = List.of("**/*.java", "**/pom.xml", "**/build.gradle",
			"**/build.gradle.kts");

	private final UnusedCodeAnalyzer analyzer;

	private final Function<Path, AutofixEngine> engines;

	private final DiagnosticMapper mapper = new DiagnosticMapper();

	private final QuickFixMapper quickFixes = new QuickFixMapper();

	private final Set<String> publishedUris = new HashSet<>();

	private final CompletableFuture<Integer> exited = new CompletableFuture<>();

	private @Nullable LanguageClient client;

	private Path projectRoot = Path.of(".").toAbsolutePath().normalize();

	private AnalysisReport lastReport = new AnalysisReport(List.of(), "", true);

	private boolean clientWatchesFiles;

	private volatile boolean shutdownRequested;

	public PruneLanguageServer() {
		this(new ConservativeUnusedCodeAnalyzer(), SourceAutofixEngine::new);
	}

	PruneLanguageServer(UnusedCodeAnalyzer analyzer) {
		this(analyzer, SourceAutofixEngine::new);
	}

	PruneLanguageServer(UnusedCodeAnalyzer analyzer, Function<Path, AutofixEngine> engines) {
		this.analyzer = analyzer;
		this.engines = engines;
	}

	@Override
	public void connect(LanguageClient client) {
		this.client = client;
	}

	private LanguageClient client() {
		return Objects.requireNonNull(client, "connect() must run before the server handles messages");
	}

	@Override
	public synchronized CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		projectRoot = resolveProjectRoot(params);
		clientWatchesFiles = supportsWatchRegistration(params);

		TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
		sync.setOpenClose(false);
		sync.setChange(TextDocumentSyncKind.None);
		sync.setSave(Boolean.TRUE);

		ServerCapabilities capabilities = new ServerCapabilities();
		capabilities.setTextDocumentSync(Either.forRight(sync));
		capabilities.setCodeActionProvider(new CodeActionOptions(List.of(CodeActionKind.QuickFix)));

		return CompletableFuture
			.completedFuture(new InitializeResult(capabilities, new ServerInfo(DiagnosticMapper.SOURCE)));
	}

	@Override
	public synchronized void initialized(InitializedParams params) {
		if (clientWatchesFiles) {
			registerFileWatchers();
		}
		refreshDiagnostics();
	}

	// Clients only report changes for watchers the server registers; without one,
	// deleting a Java file or editing pom.xml would leave stale diagnostics until the
	// next save.
	private void registerFileWatchers() {
		List<FileSystemWatcher> watchers = WATCHED_GLOBS.stream()
			.map(glob -> new FileSystemWatcher(Either.forLeft(glob)))
			.toList();
		Registration registration = new Registration("prune-java.watchers", "workspace/didChangeWatchedFiles",
				new DidChangeWatchedFilesRegistrationOptions(watchers));
		LanguageClient connected = client();
		connected.registerCapability(new RegistrationParams(List.of(registration))).exceptionally(failure -> {
			connected.logMessage(new MessageParams(MessageType.Warning,
					"prune-java could not register file watchers: " + failure.getMessage()));
			return null;
		});
	}

	private static boolean supportsWatchRegistration(InitializeParams params) {
		ClientCapabilities capabilities = params.getCapabilities();
		return capabilities != null && capabilities.getWorkspace() != null
				&& capabilities.getWorkspace().getDidChangeWatchedFiles() != null
				&& Boolean.TRUE.equals(capabilities.getWorkspace().getDidChangeWatchedFiles().getDynamicRegistration());
	}

	@Override
	public synchronized CompletableFuture<Object> shutdown() {
		shutdownRequested = true;
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void exit() {
		exited.complete(shutdownRequested ? 0 : 1);
	}

	// Exit code per LSP spec: 0 after shutdown, 1 if exit arrives without it. The copy
	// completes with the server's code but cannot complete the server's own future.
	public CompletableFuture<Integer> exited() {
		return exited.copy();
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

			@Override
			public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
				List<Diagnostic> requested = params.getContext() == null || params.getContext().getDiagnostics() == null
						? List.of() : params.getContext().getDiagnostics();
				List<CodeAction> actions = quickFixesFor(params.getTextDocument().getUri(), requested);
				return CompletableFuture
					.completedFuture(actions.stream().map(Either::<Command, CodeAction>forRight).toList());
			}
		};
	}

	private synchronized List<CodeAction> quickFixesFor(String uri, List<Diagnostic> requested) {
		return quickFixes.quickFixes(uri, requested, lastReport, projectRoot, engines.apply(projectRoot));
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
		if (shutdownRequested) {
			return;
		}

		AnalysisReport report;
		LanguageClient connected = client();
		try {
			report = analyzer.analyze(AnalysisConfig.defaultFor(projectRoot));
		}
		catch (RuntimeException failure) {
			connected.logMessage(new MessageParams(MessageType.Error,
					"prune-java could not analyze " + projectRoot + ": " + failure.getMessage()));
			return;
		}
		lastReport = report;
		Map<String, List<Diagnostic>> byUri = mapper.map(report, projectRoot);
		connected.logMessage(new MessageParams(MessageType.Info, report.summary()));

		for (String stale : publishedUris) {
			if (!byUri.containsKey(stale)) {
				connected.publishDiagnostics(new PublishDiagnosticsParams(stale, List.of()));
			}
		}
		byUri.forEach(
				(uri, diagnostics) -> connected.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics)));

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
		}
		catch (IllegalArgumentException | FileSystemNotFoundException nonLocalUri) {
			return Path.of(".").toAbsolutePath().normalize();
		}
	}

}
