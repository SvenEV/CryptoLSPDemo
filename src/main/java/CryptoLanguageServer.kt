import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.WorkspaceService
import soot.SootMethod
import soot.jimple.toolkits.ide.icfg.AbstractJimpleBasedICFG
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class MethodCodeLens(val method: SootMethod, val codeLens: CodeLens)

data class AnalysisResults(
    val diagnostics: Collection<CogniCryptDiagnostic>,
    val methodCodeLenses: Map<Path, Collection<MethodCodeLens>>,
    val icfg: AbstractJimpleBasedICFG?
)

val defaultAnalysisResults = AnalysisResults(
    emptyList(),
    emptyMap(),
    null
)

class CryptoLanguageServer(private val rulesDir: String) : LanguageServer, LanguageClientAware {
    override fun shutdown() = CompletableFuture.completedFuture<Any>(null)!!

    override fun exit() {
    }

    override fun connect(client: LanguageClient?) {
        this.client = client as CryptoLanguageClient
    }

    fun connect(client: CryptoLanguageClient?, socket: Socket) {
        connect(client)
        connectionSocket = socket
    }

    /** Keeps track of all documents belonging to the workspace currently opened in the client */
    lateinit var documentStore: ServerDocumentStore

    val logger = Logger()
    var client: CryptoLanguageClient? = null
    var connectionSocket: Socket? = null
    var rootPath: Path? = null
    var analysisResults = defaultAnalysisResults
    var analysisResultsAwaiter = CompletableFuture<Unit>()
    var configuration = defaultConfiguration

    fun notifyStaleResults(msg: String) {
        when (configuration.autoReanalyze) {
            AutoReanalyze.Never -> {} // Nothing to do
            AutoReanalyze.Always -> performAnalysis()
            AutoReanalyze.AskEveryTime -> {
                client?.showMessageRequest(ShowMessageRequestParams().apply {
                    type = MessageType.Info
                    message = "$msg, re-analyze? (You can change this behavior in the settings.)"
                    actions = listOf(MessageActionItem("Re-Analyze"))
                })?.thenApply { action ->
                    if (action.title == "Re-Analyze")
                        performAnalysis()
                }
            }
        }
    }

    fun notifyMultiWorkspaceNotSupported() {
        client?.showMessage(MessageParams(MessageType.Warning, "Multiple workspace folders are not supported by the CogniCrypt language server"))
    }

    fun invalidateDiagnostics() {
        if (analysisResultsAwaiter.isDone)
            analysisResultsAwaiter = CompletableFuture()
    }

    fun clearDiagnosticsForFile(filePath: Path) {
        val remainingDiagnostics = analysisResults.diagnostics.filter { it.location.uri.asFilePath != filePath }

        val publishParams = PublishDiagnosticsParams().apply {
            uri = filePath.toUri().toString()
            diagnostics = emptyList()
        }
        client?.publishDiagnostics(publishParams)
        analysisResults = analysisResults.copy(diagnostics = remainingDiagnostics)
        System.err.println("server:\n$publishParams")
    }

    fun performAnalysis() {
        fun publishDiagnostics(diagnostics: Collection<CogniCryptDiagnostic>) {
            diagnostics
                .map { result ->
                    Diagnostic().apply {
                        source = result.location.uri
                        message = result.message
                        range = result.location.range
                        severity = result.severity
                        relatedInformation = result.pathConditions
                    }
                }
                .groupBy { it.source }
                .forEach { sourceUri, diags ->
                    val publishParams = PublishDiagnosticsParams().apply {
                        this.uri = sourceUri
                        this.diagnostics = diags
                    }
                    client?.publishDiagnostics(publishParams)
                    System.err.println("server:\n$publishParams")
                }
        }

        val result = analyze(client, rulesDir, documentStore.rootFolder) ?: return
        publishDiagnostics(result.diagnostics)

        val methodCodeLenses = soot.Scene.v().classes
            .flatMap { klass ->
                klass.methods.mapNotNull { method ->
                    val location = tryGetSourceLocation(method)
                    if (location != null && location.range.start.line >= 0 && location.range.end.line >= 0)
                        location.uri.asFilePath to MethodCodeLens(
                            method,
                            CodeLens(
                                location.range,
                                KnownCommands.ShowCfg.asCommand(method.signature),
                                null
                            )
                        )
                    else
                        null
                }
            }
            .groupBy({ it.first }) { it.second }

        analysisResults = AnalysisResults(result.diagnostics, methodCodeLenses, result.icfg!!)
        analysisResultsAwaiter.complete(Unit)
    }

    fun diagnosticsAt(filePath: Path, position: Position) =
        analysisResults.diagnostics.filter {
            it.location.uri.asFilePath == filePath &&
                it.location.range.contains(position)
        }

    override fun getTextDocumentService() = CryptoTextDocumentService(this, { client }, rulesDir)

    override fun getWorkspaceService(): WorkspaceService = CryptoWorkspaceService(this) { client }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.logClientMsg(params.toString())
        System.err.println("client:\n$params")

        this.rootPath = params.rootUri?.asFilePath
        documentStore = ServerDocumentStore(params.rootUri.asFilePath)

        // Report server capabilities
        val result = InitializeResult(
            ServerCapabilities().apply {
                setTextDocumentSync(TextDocumentSyncKind.Full)
                documentHighlightProvider = true
                codeLensProvider = CodeLensOptions().apply { isResolveProvider = true }
                executeCommandProvider = ExecuteCommandOptions(KnownCommands.values().map { it.id })
                workspace = WorkspaceServerCapabilities(WorkspaceFoldersOptions().apply {
                    setChangeNotifications(true)
                })
            })

        System.err.println("server:\n${result.capabilities}")
        logger.logServerMsg(result.toString())
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized(params: InitializedParams?) {
        super.initialized(params)

        // Load configuration
        requestConfiguration(client!!).thenApply {
            configuration = it

            client?.registerCapability(RegistrationParams(listOf(
                Registration()
            )))

            performAnalysis()
        }
    }
}


//
// Launcher functions
//

fun createServerLauncher(server: LanguageServer, `in`: InputStream, out: OutputStream) =
    LSPLauncher.Builder<CryptoLanguageClient>()
        .setLocalService(server)
        .setRemoteInterface(CryptoLanguageClient::class.java)
        .setInput(`in`)
        .setOutput(out)!!

fun createServerLauncher(server: LanguageServer, `in`: InputStream, out: OutputStream, validate: Boolean, trace: PrintWriter) =
    createServerLauncher(server, `in`, out)
        .validateMessages(validate)
        .traceMessages(trace)!!

fun CryptoLanguageServer.launchOnStdio() =
    launchOnStream(System.`in`, System.out)

fun CryptoLanguageServer.launchOnStream(inputStream: InputStream, outputStream: OutputStream) {
    val launcher = createServerLauncher(this,
        inputStream.logStream("magpie.in"),
        outputStream.logStream("magpie.out"),
        true,
        PrintWriter(System.err)).create()
    connect(launcher.remoteProxy)
    launcher.startListening()
}

fun CryptoLanguageServer.launchOnSocketPort(port: Int) {
    try {
        val serverSocket = ServerSocket(port)
        val connectionSocket = serverSocket.accept()
        val launcher = createServerLauncher(this,
            connectionSocket.getInputStream().logStream("magpie.in"),
            connectionSocket.getOutputStream().logStream("magpie.out")).create()
        connect(launcher.remoteProxy, connectionSocket)
        launcher.startListening()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}