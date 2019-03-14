import com.ibm.wala.classLoader.Module
import com.ibm.wala.classLoader.SourceFileModule
import com.ibm.wala.util.io.TemporaryFile
import crypto.analysis.errors.AbstractError
import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import magpiebridge.core.AnalysisResult
import magpiebridge.core.JavaProjectService
import magpiebridge.core.MagpieServer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.io.File
import java.nio.file.Paths
import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

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

data class CryptoTextDocumentState(val clientUri: String, val serverUri: String, val text: String, val module: SourceFileModule)

class CryptoTextDocumentService(private val server: CryptoLanguageServer, private val rulesDir: String) : TextDocumentService {

    private lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument

        if (doc.languageId == "java") {
            // Copy document to temporary file
            val file = File.createTempFile("temp", ".java").apply { deleteOnExit() }
            TemporaryFile.stringToFile(file, doc.text)
            val module = SourceFileModule(file, doc.uri, null)
            val serverUri = Paths.get(file.toURI()).toUri().toString()

            val state = CryptoTextDocumentState(doc.uri, serverUri, doc.text, module)
            server.documentState[doc.uri] = state
            server.serverClientUri[serverUri] = doc.uri
        }

        diagnostics = analyze(rulesDir, server.documentState.values.map { it.module })
        server.consume(diagnostics, "CogniCrypt")
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentState.remove(params.textDocument.uri)?.let {
            server.serverClientUri.remove(it.serverUri)
        }

    }

    override fun didChange(p0: DidChangeTextDocumentParams?) {
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
            if (surroundingDiagnostic != null)
                surroundingDiagnostic.highlightPositions
                    .map {
                        val start = Position(it.firstLine - 1, it.firstCol - 1)
                        val end = Position(it.lastLine - 1, it.lastCol)
                        DocumentHighlight(Range(start, end), DocumentHighlightKind.Text)
                    }
                    .toMutableList()
            else
                mutableListOf())
    }
}

class CryptoLanguageServer(private val rulesDir: String) : MagpieServer() {

    val documentState = mutableMapOf<String, CryptoTextDocumentState>()
    val serverClientUri = mutableMapOf<String, String>()

    override fun getTextDocumentService() = CryptoTextDocumentService(this, rulesDir)

    override fun createDiagnosticConsumer(diagList: MutableList<Diagnostic>, source: String) = Consumer<AnalysisResult> { result ->
        val d = Diagnostic()
        d.message = result.toString(false)
        d.range = getLocationFrom(result.position()).range
        d.source = source
        val relatedList = ArrayList<DiagnosticRelatedInformation>()
        for (related in result.related()) {
            val di = DiagnosticRelatedInformation()
            di.location = getLocationFrom(related.fst)
            di.message = related.snd
            relatedList.add(di)
        }
        d.relatedInformation = relatedList
        d.severity = result.severity()
        if (!diagList.contains(d)) {
            diagList.add(d)
        }
        val pdp = PublishDiagnosticsParams()
        pdp.diagnostics = diagList
        var serverUri = result.position().getURL().toString()
        if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
            // take care of uri in windows
            if (!serverUri.startsWith("file:///")) {
                serverUri = serverUri.replace("file://", "file:///")
            }
        }
        val clientUri = serverClientUri[serverUri]
        pdp.uri = clientUri
        client.publishDiagnostics(pdp)
        logger.logServerMsg(pdp.toString())
        System.err.println("server:\n$pdp")
    }
}

object CryptoDemoMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val ruleDirPath = TestMain.ruleDirPath
        // String ruleDirPath = args[0];
        println("server started")
        val language = "java"
        val javaProjectService = JavaProjectService()
        val server = CryptoLanguageServer("E:\\Projects\\Masterarbeit\\CryptoLSPDemo\\JCA_rules")
        server.addProjectService(language, javaProjectService)
        server.addAnalysis(language, CryptoServerAnalysis(ruleDirPath))
        server.launchOnStdio()
    }

}
