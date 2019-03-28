import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class CryptoLanguageServer(private val rulesDir: String) : LanguageServer, LanguageClientAware {
    override fun shutdown() = CompletableFuture.completedFuture<Any>(null)!!

    override fun exit() {
    }

    override fun connect(client: LanguageClient?) {
        this.client = client
    }

    fun connect(client: LanguageClient?, socket: Socket) {
        connect(client)
        connectionSocket = socket
    }

    /** Keeps track of all documents belonging to the workspace currently opened in the client */
    lateinit var documentStore: ServerDocumentStore

    val logger = Logger()
    var client: LanguageClient? = null
    var connectionSocket: Socket? = null
    var rootPath: Path? = null
    var diagnostics: Collection<CogniCryptDiagnostic> = emptyList()

    fun notifyStaleResults(msg: String) {
        client?.showMessageRequest(ShowMessageRequestParams().apply {
            type = MessageType.Info
            message = "$msg, re-analyze?"
            actions = listOf(
                MessageActionItem("Re-Analyze")
            )
        })?.thenApply { action ->
            if (action.title == "Re-Analyze") {
                diagnostics = analyze(client, rulesDir, documentStore.rootFolder)
                notifyDiagnostics()
            }
        }
    }

    fun notifyMultiWorkspaceNotSupported() {
        client?.showMessage(MessageParams(MessageType.Warning, "Multiple workspace folders are not supported by the CogniCrypt language server"))
    }

    fun notifyDiagnostics() {
        diagnostics
            .map { result ->
                Diagnostic().apply {
                    source = result.location.uri
                    message = result.message
                    range = result.location.range
                    severity = result.severity
                    relatedInformation = result.highlightLocations.map { related ->
                        DiagnosticRelatedInformation().apply {
                            this.location = related
                            message = "Data Flow Path"
                        }
                    }
                }
            }
            .groupBy { it.source }
            .forEach { sourceUri, diags ->
                val publishParams = PublishDiagnosticsParams().apply {
                    uri = sourceUri
                    diagnostics = diags
                }
                client?.publishDiagnostics(publishParams)
                System.err.println("server:\n$publishParams")
            }
    }

    fun diagnosticsAt(filePath: Path, position: Position) =
        diagnostics.filter {
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
        diagnostics = analyze(client, rulesDir, documentStore.rootFolder)
        notifyDiagnostics()
    }
}


//
// Launcher functions
//

fun CryptoLanguageServer.launchOnStdio() =
    launchOnStream(System.`in`, System.out)

fun CryptoLanguageServer.launchOnStream(inputStream: InputStream, outputStream: OutputStream) {
    val launcher = LSPLauncher.createServerLauncher(this,
        inputStream.logStream("magpie.in"),
        outputStream.logStream("magpie.out"),
        true,
        PrintWriter(System.err))
    connect(launcher.remoteProxy)
    launcher.startListening()
}

fun CryptoLanguageServer.launchOnSocketPort(host: String, port: Int) {
    try {
        val connectionSocket = Socket(host, port)
        val launcher = LSPLauncher.createServerLauncher(this,
            connectionSocket.getInputStream().logStream("magpie.in"),
            connectionSocket.getOutputStream().logStream("magpie.out"))
        connect(launcher.remoteProxy, connectionSocket)
        launcher.startListening()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}