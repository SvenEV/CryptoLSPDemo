import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class CryptoWorkspaceService(
    private val server: CryptoLanguageServer,
    private val client: () -> LanguageClient?) : WorkspaceService {

    override fun didChangeWatchedFiles(args: DidChangeWatchedFilesParams) {
        server.invalidateDiagnostics()
        args.changes.forEach {
            when (it.type!!) {
                FileChangeType.Created -> {}
                FileChangeType.Changed -> server.clearDiagnosticsForFile(it.uri.asFilePath)
                FileChangeType.Deleted -> server.clearDiagnosticsForFile(it.uri.asFilePath)
            }
        }
        server.notifyStaleResults("Files changed")
    }

    override fun didChangeConfiguration(args: DidChangeConfigurationParams) {
    }

    override fun didChangeWorkspaceFolders(args: DidChangeWorkspaceFoldersParams) {
        server.notifyMultiWorkspaceNotSupported()
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        when (KnownCommands.tryParse(params.command)) {
            KnownCommands.Debug ->
                client()?.showMessage(MessageParams(MessageType.Info, "Watched files:\n" + server.documentStore.documents
                    .joinToString("\n") { it.sourcePath.toString() }))

            KnownCommands.Reanalyze -> {
                server.invalidateDiagnostics()
                server.performAnalysis()
            }
        }
        return CompletableFuture.completedFuture(null)
    }
}
