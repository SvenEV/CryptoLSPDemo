package languageserver

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/**
 * Extends the [LanguageClient] interface with CogniCrypt-specific protocol extensions.
 */
interface CryptoLanguageClient : LanguageClient {
    @JsonNotification("cognicrypt/showCFG")
    fun showCfg(args: ShowCfgParams)

    @JsonNotification("cognicrypt/status")
    fun setStatusBarMessage(args: StatusMessage?)

    @JsonNotification("cognicrypt/treeData")
    fun publishTreeData(args: PublishTreeDataParams)

    @JsonRequest("cognicrypt/quickPick")
    fun quickPick(args: QuickPickParams): CompletableFuture<QuickPickResult>

    @JsonNotification("cognicrypt/showTextDocument")
    fun showTextDocument(args: ShowTextDocumentParams)

    @JsonRequest("cognicrypt/connectToJavaExtension")
    fun connectToJavaExtension(): CompletableFuture<ConnectToJavaExtensionResult>
}
