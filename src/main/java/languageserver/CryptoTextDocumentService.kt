package languageserver

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class CryptoTextDocumentService(
    private val server: CryptoLanguageServer,
    private val client: () -> LanguageClient?,
    private val rulesDir: String) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
        server.documentStore.add(params.textDocument.uri.asFilePath)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentStore.remove(params.textDocument.uri.asFilePath)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Diagnostics are no longer valid after code changes
        // (user must save the document to trigger re-analysis)
        server.project?.invalidateDiagnostics()
        server.clearDiagnosticsForFile(params.textDocument.uri.asFilePath)
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<MutableList<out DocumentHighlight>> {
        val surroundingDiagnostic = server.project!!.diagnosticsAt(position.textDocument.uri.asFilePath, position.position).firstOrNull()

        val dataFlowPath = surroundingDiagnostic?.dataFlowPath?.mapNotNull {
            if (it.location.uri.asFilePath == position.textDocument.uri.asFilePath)
                DocumentHighlight(it.location.range, DocumentHighlightKind.Read)
            else
                null
        }?.asSequence() ?: emptySequence()

        val ifStatements = surroundingDiagnostic?.pathConditions?.mapNotNull {
            if (it.location.uri.asFilePath == position.textDocument.uri.asFilePath)
                DocumentHighlight(it.location.range, DocumentHighlightKind.Text)
            else
                null
        }?.asSequence() ?: emptySequence()

        return CompletableFuture.completedFuture((dataFlowPath + ifStatements).toMutableList())
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> {
        // Wait until diagnostics are available, then compute code lenses
        return server.project!!.analysisResultsAwaiter.thenApply {
            val analysisResults = server.project!!.analysisResults

            val methodLenses = analysisResults.methodCodeLenses[params.textDocument.uri.asFilePath]
                ?.map { it.codeLens }
                ?: emptyList()

            val diagnosticLenses = analysisResults.diagnostics
                .asSequence()
                .filter { it.location.uri.asFilePath == params.textDocument.uri.asFilePath }
                .groupBy { it.location.range.start.line }
                .map { (lineNumber, diags) ->
                    val pos = Position(lineNumber, 0)
                    val range = Range(pos, pos)
                    val message = when (diags.size) {
                        1 -> diags[0].summary
                        else -> "${diags.size} problems"
                    }
                    CodeLens(range, KnownCommands.FilterDiagnostics.asCommandWithTitle(message, diags.map { it.id }), null)
                }

            (diagnosticLenses + methodLenses).toMutableList()
        }
    }
}