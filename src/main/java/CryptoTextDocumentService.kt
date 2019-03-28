import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class CryptoTextDocumentService(
    private val server: CryptoLanguageServer,
    private val client: () -> LanguageClient?,
    private val rulesDir: String) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<MutableList<out DocumentHighlight>> {
        val surroundingDiagnostic = server.diagnosticsAt(position.textDocument.uri.asFilePath, position.position).firstOrNull()

        return CompletableFuture.completedFuture(
            surroundingDiagnostic?.highlightLocations?.mapNotNull {
                if (it.uri == position.textDocument.uri)
                    DocumentHighlight(it.range, DocumentHighlightKind.Read)
                else
                    null
            }?.toMutableList() ?: mutableListOf())
    }

    override fun hover(position: TextDocumentPositionParams?): CompletableFuture<Hover> {
        return CompletableFuture.completedFuture(Hover(listOf()))
    }

    override fun codeLens(params: CodeLensParams?): CompletableFuture<MutableList<out CodeLens>> {
        val debugLens = CodeLens(
            Range(Position(0, 0), Position(0, 0)),
            KnownCommands.Debug.asCommand,
            null);

        val lenses = server.diagnostics
            .map { CodeLens(it.position().asRange, Command(it.message.substring(0, it.message.indexOf(". ")), "cmd"), null) }
            .plus(debugLens)
            .toMutableList()

        return CompletableFuture.completedFuture(lenses)
    }

    override fun documentSymbol(params: DocumentSymbolParams?): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFuture.completedFuture(mutableListOf())
    }
}