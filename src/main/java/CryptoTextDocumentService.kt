import com.ibm.wala.classLoader.Module
import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.util.concurrent.CompletableFuture

private fun analyze(rulesDir: String, sourceModules: Collection<Module>): Collection<CogniCryptDiagnostic> {
    val transformer = CryptoTransformer(rulesDir)
    loadSourceCode(sourceModules)
    runSootPacks(transformer)

    transformer.diagnostics.forEach { System.err.println(it) }
    return transformer.diagnostics
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

class CryptoTextDocumentService(private val server: CryptoLanguageServer, private val rulesDir: String) : TextDocumentService {

    private lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument

        if (doc.languageId == "java") {
            server.documentStore.add(doc.uri, doc.text)
        }

        diagnostics = analyze(rulesDir, server.documentStore.documents.map { it.module })
        server.clearDiagnostics(server.documentStore.getByClientUri(params.textDocument.uri)!!.serverUri, "CogniCrypt")
        server.consume(diagnostics, "CogniCrypt")
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentStore.remove(params.textDocument.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        server.documentStore.update(params.textDocument.uri, params.contentChanges[0].text);
        diagnostics = analyze(rulesDir, server.documentStore.documents.map { it.module })
        server.clearDiagnostics(server.documentStore.getByClientUri(params.textDocument.uri)!!.serverUri, "CogniCrypt")
        server.consume(diagnostics, "CogniCrypt")
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