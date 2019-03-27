import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import magpiebridge.core.AnalysisResult
import magpiebridge.core.MagpieServer
import magpiebridge.core.Utils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

enum class KnownCommands(val id: String, val title: String) {
    Debug("lspdebug", "LSP Debug");

    val asCommand get() = Command(title, id)

    companion object {
        fun tryParse(s: String) = KnownCommands.values().firstOrNull { it.id == s }
    }
}

private fun analyze(client: LanguageClient?, rulesDir: String, rootFolder: Path): Collection<CogniCryptDiagnostic> =
    try {
        val transformer = CryptoTransformer(rulesDir)
        loadSourceCode(rootFolder)
        runSootPacks(transformer)
        transformer.diagnostics.forEach { System.err.println(it) }
        transformer.diagnostics
    } catch (e: Exception) {
        val trace = ExceptionUtils.getStackTrace(e)
        client?.showMessage(MessageParams(MessageType.Error, "Analysis failed:\n$trace"))
        emptyList()
    }

private fun loadSourceCode(rootFolder: Path) {
    val loader = WalaClassLoader(rootFolder.toString())
    val sootClasses = loader.sootClasses
    val jimpleConverter = JimpleConverter(sootClasses)
    jimpleConverter.convertAllClasses()
}

private fun runSootPacks(t: Transformer) {
    PackManager.v().getPack("wjtp").add(Transform("wjtp.cognicrypt", t))
    PackManager.v().getPack("cg").apply()
    PackManager.v().getPack("wjtp").apply()
}

class CryptoLanguageServer(private val rulesDir: String) : MagpieServer() {

    /** Keeps track of all documents belonging to the workspace currently opened in the client */
    lateinit var documentStore: ServerDocumentStore

    var diagnosticList: Collection<CogniCryptDiagnostic> = emptyList()

    fun notifyStaleResults(msg: String) {
        client?.showMessageRequest(ShowMessageRequestParams().apply {
            type = MessageType.Info
            message = "$msg, re-analyze?"
            actions = listOf(
                MessageActionItem("Re-Analyze")
            )
        })?.thenApply { action ->
            if (action.title == "Re-Analyze") {
                diagnosticList = analyze(client, rulesDir, documentStore.rootFolder)
                notifyDiagnostics()
            }
        }
    }

    fun notifyMultiWorkspaceNotSupported() {
        client?.showMessage(MessageParams(MessageType.Warning, "Multiple workspace folders are not supported by the CogniCrypt language server"))
    }

    fun notifyDiagnostics() {
        val diags = diagnosticList
            .map { result ->
                Diagnostic().apply {
                    this.source = result.position().url.toStringWithWindowsFix()
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
            }
            .groupBy { it.source }

        diags.forEach { sourceUri, diags ->
            val publishParams = PublishDiagnosticsParams().apply {
                uri = sourceUri
                diagnostics = diags
            }
            client.publishDiagnostics(publishParams)
            System.err.println("server:\n$publishParams")
        }
    }

    fun diagnosticsAt(filePath: Path, position: Position) =
        diagnosticList.filter {
            it.position().url.asFilePath == filePath &&
                it.position().asRange.contains(position)
        }

    override fun getTextDocumentService() = CryptoTextDocumentService(this, { client }, rulesDir)

    override fun getWorkspaceService(): WorkspaceService = CryptoWorkspaceService(this, { client })

    override fun createDiagnosticConsumer(diagList: MutableList<Diagnostic>?, source: String?) = Consumer<AnalysisResult> {}

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        super.initialize(params).thenApply { result ->
            val rootFolder = params.rootUri.asFilePath
            documentStore = ServerDocumentStore(rootFolder)

            result.capabilities.executeCommandProvider = ExecuteCommandOptions(KnownCommands.values().map { it.id })
            result.capabilities.workspace = WorkspaceServerCapabilities(WorkspaceFoldersOptions().apply {
                setChangeNotifications(true)
            })
            result
        }

    override fun initialized(params: InitializedParams?) {
        super.initialized(params)
        diagnosticList = analyze(client, rulesDir, documentStore.rootFolder)
        notifyDiagnostics()
    }
}
