import com.ibm.wala.classLoader.SourceFileModule
import com.ibm.wala.util.io.TemporaryFile
import magpiebridge.core.AnalysisResult
import magpiebridge.core.JavaProjectService
import magpiebridge.core.MagpieServer
import magpiebridge.core.Utils
import org.eclipse.lsp4j.*
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

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

    fun update(clientUri: String, newText: String): Boolean {
        val doc = documentState[clientUri]
        return if (doc == null) {
            false
        } else {
            // Update text in temporary file
            TemporaryFile.stringToFile(doc.module.file, newText)
            documentState[clientUri] = doc.copy(text = newText)
            true
        }
    }

    val documents get() = documentState.values.toList()
    fun getByClientUri(clientUri: String) = documentState[clientUri]
    fun getByServerUri(serverUri: String) = serverClientUri[serverUri]?.let { getByClientUri(it) }
}

private fun fixFileUriOnWindows(uri: String) = when {
    System.getProperty("os.name").toLowerCase().indexOf("win") >= 0 && !uri.startsWith("file:///") ->
        // take care of uri in windows
        uri.replace("file://", "file:///")
    else ->
        uri
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
