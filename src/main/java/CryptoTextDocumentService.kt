import com.ibm.wala.classLoader.Module
import crypto.pathconditions.z3.constructDouble
import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.util.concurrent.CompletableFuture

private fun analyze(client: LanguageClient?, rulesDir: String, sourceModules: Collection<Module>): Collection<CogniCryptDiagnostic> =
    try {
        val transformer = CryptoTransformer(rulesDir)
        loadSourceCode(sourceModules)
        runSootPacks(transformer)
        transformer.diagnostics.forEach { System.err.println(it) }
        transformer.diagnostics
    } catch (e: Exception) {
        client?.showMessage(MessageParams(MessageType.Error, "Analysis failed:\n$e"))
        emptyList()
    }

private fun loadSourceCode(sourceModules: Collection<Module>) {
    val loader = WalaClassLoader(sourceModules)
    val sootClasses = loader.sootClasses
    val jimpleConverter = JimpleConverter(sootClasses)
    jimpleConverter.convertAllClasses()
}

private fun runSootPacks(t: Transformer) {
    PackManager.v().getPack("wjtp").add(Transform("wjtp.cognicrypt", t))
    PackManager.v().getPack("cg").apply()
    PackManager.v().getPack("wjtp").apply()
}

class CryptoTextDocumentService(
    private val server: CryptoLanguageServer,
    private val client: () -> LanguageClient?,
    private val rulesDir: String) : TextDocumentService {

    private var diagnostics: Collection<CogniCryptDiagnostic> = emptyList()

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument

        if (doc.languageId == "java") {
            server.documentStore.add(doc.uri, doc.text)
        }

        client()?.showMessage(MessageParams(MessageType.Info, "Document opened, analyzing..."))
        diagnostics = analyze(client(), rulesDir, server.documentStore.documents.map { it.module })
        server.clearDiagnostics(server.documentStore.getByClientUri(params.textDocument.uri)!!.serverUri, "CogniCrypt")
        server.consume(diagnostics, "CogniCrypt")
        client()?.showMessage(MessageParams(MessageType.Info, "Analysis done after didOpen()"))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        client()?.showMessageRequest(ShowMessageRequestParams().apply {
            type = MessageType.Info
            message = "Document changed, re-analyze?"
            actions = listOf(
                MessageActionItem("Re-Analyze")
            )
        })?.thenApply {
            if (it.title == "Re-Analyze") {
                diagnostics = analyze(client(), rulesDir, server.documentStore.documents.map { it.module })
                server.clearDiagnostics(server.documentStore.getByClientUri(params.textDocument.uri)!!.serverUri, "CogniCrypt")
                server.consume(diagnostics, "CogniCrypt")
                client()?.showMessage(MessageParams(MessageType.Info, "Analysis done after didChange()"))
            }
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentStore.remove(params.textDocument.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        server.documentStore.update(params.textDocument.uri, params.contentChanges[0].text);
        server.clearDiagnostics(server.documentStore.getByClientUri(params.textDocument.uri)!!.serverUri, "CogniCrypt")
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<MutableList<out DocumentHighlight>> {
        val position = position.position
        val surroundingDiagnostic = diagnostics.firstOrNull {
            position.line + 1 >= it.position().firstLine &&
                position.line + 1 <= it.position().lastLine &&
                position.character + 1 >= it.position().firstCol &&
                position.character <= it.position().lastCol
        }

        return CompletableFuture.completedFuture(
            surroundingDiagnostic?.highlightPositions?.map {
                val start = Position(it.firstLine - 1, it.firstCol - 1)
                val end = Position(it.lastLine - 1, it.lastCol)
                DocumentHighlight(Range(start, end), DocumentHighlightKind.Text)
            }?.toMutableList() ?: mutableListOf())
    }
}