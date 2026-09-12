package com.patbaumgartner.prune.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;

public final class PruneLspApplication {

    private PruneLspApplication() {
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        InputStream in = System.in;
        PrintStream out = System.out;
        // stdout is the protocol channel; any stray print would corrupt it.
        System.setOut(System.err);

        PruneLanguageServer server = new PruneLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        server.exited().thenAccept(System::exit);
        launcher.startListening().get();
        System.exit(0);
    }
}
