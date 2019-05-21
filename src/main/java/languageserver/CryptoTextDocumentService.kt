package languageserver

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class CryptoTextDocumentService(private val server: CryptoLanguageServer) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
        server.documentStore.add(params.textDocument.uri.asFilePath)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentStore.remove(params.textDocument.uri.asFilePath)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        GlobalScope.launch {
            // Diagnostics are no longer valid after code changes
            // (user must save the document to trigger re-analysis)
            server.clearDiagnosticsForFile(params.textDocument.uri.asFilePath)
            server.project.getAsync().invalidateDiagnostics()
        }
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<MutableList<out DocumentHighlight>> = GlobalScope.future {
        val surroundingDiagnostic = server.project.getAsync().diagnosticsAt(position.textDocument.uri.asFilePath, position.position).firstOrNull()

        val dataFlowPath = surroundingDiagnostic?.dataFlowPath?.mapNotNull {
            if (it.location.uri.asFilePath == position.textDocument.uri.asFilePath)
                DocumentHighlight(it.location.range, DocumentHighlightKind.Read)
            else
                null
        }?.asSequence() ?: emptySequence()

        val ifStatements = (surroundingDiagnostic?.pathConditions as? PathConditionsSuccess)?.items?.flatMap { cond ->
            cond.branchLocations
                .filter { it.uri.asFilePath == position.textDocument.uri.asFilePath }
                .map { DocumentHighlight(it.range, DocumentHighlightKind.Text) }
        }?.asSequence() ?: emptySequence()

        (dataFlowPath + ifStatements).toMutableList()
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> = GlobalScope.future {
        // Wait until diagnostics are available, then compute code lenses
        server.project.getAsync().analysisResultsAwaiter.getAsync()
        val analysisResults = server.project.getAsync().analysisResults

        val methodLenses = analysisResults.methodCodeLenses[params.textDocument.uri.asFilePath]
            ?.map { it.codeLens }
            ?: emptyList()

        val diagnosticLenses = analysisResults.diagnostics
            .asSequence()
            .filter { it.location?.uri?.asFilePath == params.textDocument.uri.asFilePath }
            .groupBy { it.location!!.range.start.line }
            .map { (lineNumber, diags) ->
                val pos = Position(lineNumber, 0)
                val range = Range(pos, pos)
                val message = when (diags.size) {
                    1 -> diags[0].summary
                    else -> "${diags.size} problems"
                }
                val commandArgs = DiagnosticCodeLens(params.textDocument.uri, lineNumber, diags.map { it.id })
                CodeLens(range, KnownCommands.FilterDiagnostics.asCommandWithTitle(message, commandArgs), null)
            }

        (diagnosticLenses + methodLenses).toMutableList()
    }
}

data class DiagnosticCodeLens(val fileUri: String, val lineNumber: Int, val diagnosticIds: List<String>)