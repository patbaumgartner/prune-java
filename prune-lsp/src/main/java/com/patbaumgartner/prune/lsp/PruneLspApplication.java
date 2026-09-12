package com.patbaumgartner.prune.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.PrintStream;
import java.util.concurrent.ExecutionException;

public final class PruneLspApplication {

	private PruneLspApplication() {
	}

	public static void main(String[] args) throws InterruptedException, ExecutionException {
		PruneLanguageServer server = new PruneLanguageServer();
		Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, claimStdout());
		server.connect(launcher.getRemoteProxy());
		// The client normally ends the session with exit; one that only closes the pipe
		// ends the listener instead.
		Thread exitOnRequest = new Thread(() -> System.exit(server.exited().join()), "prune-lsp-exit");
		exitOnRequest.setDaemon(true);
		exitOnRequest.start();
		launcher.startListening().get();
		System.exit(0);
	}

	// stdout is the protocol channel; any stray print would corrupt it.
	private static PrintStream claimStdout() {
		PrintStream wire = System.out;
		System.setOut(System.err);
		return wire;
	}

}
