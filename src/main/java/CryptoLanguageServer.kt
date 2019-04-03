import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
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
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class MethodCodeLens(val method: SootMethod, val codeLens: CodeLens)

data class AnalysisResults(
    val diagnostics: Collection<CogniCryptDiagnostic>,
    val methodCodeLenses: Map<Path, Collection<MethodCodeLens>>,
    val icfg: AbstractJimpleBasedICFG
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
    var analysisResults: AnalysisResults? = null
    var diagnosticsAwaiter: CompletableFuture<Unit> = CompletableFuture()
    var autoReanalyze = false

    fun notifyStaleResults(msg: String) {
        if (autoReanalyze) {
            performAnalysis()
        } else {
            val optionOnce = "Re-Analyze"
            val optionAlways = "Always (don't ask again)"

            client?.showMessageRequest(ShowMessageRequestParams().apply {
                type = MessageType.Info
                message = "$msg, re-analyze?"
                actions = listOf(
                    MessageActionItem(optionOnce),
                    MessageActionItem(optionAlways)
                )
            })?.thenApply { action ->
                when (action.title) {
                    optionOnce -> performAnalysis()
                    optionAlways -> {
                        performAnalysis()
                        autoReanalyze = true
                    }
                }
            }
        }
    }

    fun notifyMultiWorkspaceNotSupported() {
        client?.showMessage(MessageParams(MessageType.Warning, "Multiple workspace folders are not supported by the CogniCrypt language server"))
    }

    fun invalidateDiagnostics() {
        if (diagnosticsAwaiter.isDone)
            diagnosticsAwaiter = CompletableFuture()
    }

    fun clearDiagnosticsForFile(filePath: Path) {
        val remainingDiagnostics = analysisResults?.diagnostics
            ?.filter { it.location.uri.asFilePath != filePath }
            ?: emptyList()

        val publishParams = PublishDiagnosticsParams().apply {
            uri = filePath.toUri().toString()
            diagnostics = emptyList()
        }
        client?.publishDiagnostics(publishParams)
        analysisResults = analysisResults?.copy(diagnostics = remainingDiagnostics)
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
        diagnosticsAwaiter.complete(Unit)
    }

    fun diagnosticsAt(filePath: Path, position: Position) =
        analysisResults?.diagnostics?.filter {
            it.location.uri.asFilePath == filePath &&
                it.location.range.contains(position)
        } ?: emptyList()

    override fun getTextDocumentService() = CryptoTextDocumentService(this, { client }, rulesDir)

    override fun getWorkspaceService(): WorkspaceService = CryptoWorkspaceService(this) { client }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.logClientMsg(params.toString())
        System.err.println("client:\n$params")

        this.rootPath = params.rootUri?.asFilePath
        documentStore = ServerDocumentStore(params.rootUri.asFilePath)

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
        performAnalysis()
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

fun CryptoLanguageServer.launchOnSocketPort(host: String, port: Int) {
    try {
        val connectionSocket = Socket(host, port)
        val launcher = createServerLauncher(this,
            connectionSocket.getInputStream().logStream("magpie.in"),
            connectionSocket.getOutputStream().logStream("magpie.out")).create()
        connect(launcher.remoteProxy, connectionSocket)
        launcher.startListening()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}