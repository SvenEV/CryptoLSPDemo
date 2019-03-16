import com.ibm.wala.classLoader.Module
import com.ibm.wala.classLoader.SourceFileModule
import com.ibm.wala.util.io.TemporaryFile
import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import magpiebridge.core.AnalysisResult
import magpiebridge.core.JavaProjectService
import magpiebridge.core.MagpieServer
import magpiebridge.core.Utils
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

private fun fixFileUriOnWindows(uri: String) = when {
    System.getProperty("os.name").toLowerCase().indexOf("win") >= 0 && !uri.startsWith("file:///") ->
        // take care of uri in windows
        uri.replace("file://", "file:///")
    else ->
        uri
}

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

class ServerDocumentStore {
    private val documentState = mutableMapOf<String, CryptoTextDocumentState>()
    private val serverClientUri = mutableMapOf<String, String>()

    fun add(clientUri: String, text: String) {
        // Copy document to temporary file
        val file = File.createTempFile("temp", ".java").apply { deleteOnExit() }
        TemporaryFile.stringToFile(file, text)
        val module = SourceFileModule(file, clientUri, null)
        val serverUri = Paths.get(file.toURI()).toUri().toString()

        val state = CryptoTextDocumentState(clientUri, serverUri, text, module)
        documentState[clientUri] = state
        serverClientUri[serverUri] = clientUri
    }

    fun remove(clientUri: String) =
        documentState.remove(clientUri)?.let {
            serverClientUri.remove(it.serverUri)
            true
        } ?: false

    val documents get() = documentState.values.toList()
    fun getByClientUri(clientUri: String) = documentState[clientUri]
    fun getByServerUri(serverUri: String) = serverClientUri[serverUri]?.let { getByClientUri(it) }
}

class CryptoTextDocumentService(private val server: CryptoLanguageServer, private val rulesDir: String) : TextDocumentService {

    private lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument

        if (doc.languageId == "java") {
            server.documentStore.add(doc.uri, doc.text)
        }

        diagnostics = analyze(rulesDir, server.documentStore.documents.map { it.module })
        server.consume(diagnostics, "CogniCrypt")
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        server.documentStore.remove(params.textDocument.uri)
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

    /** Keeps track of all documents currently opened in the client */
    val documentStore = ServerDocumentStore()

    override fun getTextDocumentService() = CryptoTextDocumentService(this, rulesDir)

    override fun createDiagnosticConsumer(diagList: MutableList<Diagnostic>, source: String) = Consumer<AnalysisResult> { result ->
        val diag = Diagnostic().apply {
            this.source = source
            message = result.toString(false)
            range = Utils.getLocationFrom(result.position()).range
            severity = result.severity()
            relatedInformation = result.related().map { related ->
                DiagnosticRelatedInformation().apply {
                    location = Utils.getLocationFrom(related.fst)
                    message = related.snd
                }
            }
        }

        if (!diagList.contains(diag)) {
            diagList.add(diag)
        }

        val serverUri = fixFileUriOnWindows(result.position().url.toString())
        val publishParams = PublishDiagnosticsParams().apply {
            diagnostics = diagList
            uri = documentStore.getByServerUri(serverUri)!!.clientUri
        }

        client.publishDiagnostics(publishParams)
        logger.logServerMsg(publishParams.toString())
        System.err.println("server:\n$publishParams")
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
