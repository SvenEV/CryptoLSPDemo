import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class CryptoWorkspaceService(
    private val server: CryptoLanguageServer,
    private val client: () -> LanguageClient?) : WorkspaceService {

    override fun didChangeWatchedFiles(args: DidChangeWatchedFilesParams) {
        server.notifyStaleResults("Files changed")
        args.changes.forEach { change ->
            when (change.type!!) {
                FileChangeType.Created -> server.documentStore.add(change.uri.asFilePath)
                FileChangeType.Deleted -> server.documentStore.remove(change.uri.asFilePath)
                FileChangeType.Changed -> {} // Nothing to do
            }
        }
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
        }
        return CompletableFuture.completedFuture(null)
    }
}
