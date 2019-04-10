package languageserver

import crypto.pathconditions.debug.prettyPrintRefined
import languageserver.workspace.AnalysisResults
import languageserver.workspace.MethodCodeLens
import languageserver.workspace.WorkspaceProject
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
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class CryptoLanguageServer(private val rulesDir: String) : LanguageServer, LanguageClientAware {
    override fun shutdown() = CompletableFuture.completedFuture<Any>(null)!!

    override fun exit() {}

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
    var project: WorkspaceProject? = null // represents the single workspace root folder opened in the editor (multi-root workspaces are not yet supported)
    var configuration = defaultConfiguration

    fun notifyStaleResults(msg: String) {
        when (configuration.autoReanalyze) {
            AutoReanalyze.Never -> {
            } // Nothing to do
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

    fun clearDiagnosticsForFile(filePath: Path) {
        val publishParams = project!!.clearDiagnosticsForFile(filePath)
        client?.publishDiagnostics(publishParams)
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

            val tree = diagnostics.map { diag ->
                TreeViewNode(
                    label = "⚠ ${diag.summary}",
                    collapsibleState = TreeItemCollapsibleState.Collapsed,
                    children = listOf(
                        TreeViewNode(diag.message),
                        TreeViewNode(
                            label = "🏁 Data Flow Path",
                            collapsibleState = TreeItemCollapsibleState.Collapsed,
                            children = diag.dataFlowPath.map {
                                TreeViewNode(
                                    label = "◼ ${it.statement.prettyPrintRefined()}",
                                    command = Command("Go To", "cognicrypt/goto", listOf(it.location)))
                            }),
                        TreeViewNode(
                            label = "💡 Path Conditions",
                            collapsibleState = TreeItemCollapsibleState.Collapsed,
                            children = diag.pathConditions.map {
                                TreeViewNode(it.message)
                            })
                    )
                )
            }

            client?.publishTreeData(PublishTreeDataParams("cognicrypt.diagnostics", tree))
        }

        val result = analyze(client, rulesDir, project!!.projectPaths) ?: return
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

        project?.updateAnalysisResults(AnalysisResults(result.diagnostics, methodCodeLenses, result.icfg!!))
        client?.setStatusBarMessage(null)
    }

    override fun getTextDocumentService() = CryptoTextDocumentService(this, { client }, rulesDir)

    override fun getWorkspaceService(): WorkspaceService = CryptoWorkspaceService(this) { client }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.logClientMsg(params.toString())
        System.err.println("client:\n$params")

        client?.setStatusBarMessage(StatusMessage("Initializing project..."))

        project = WorkspaceProject.create(params.rootUri.asFilePath)
        documentStore = ServerDocumentStore()

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

        client?.publishTreeData(PublishTreeDataParams("cognicrypt.info", listOf(
            TreeViewNode(
                label = "📁 Source Path",
                collapsibleState = TreeItemCollapsibleState.Expanded,
                children = project!!.projectPaths.sourcePath.map {
                    TreeViewNode(it.toString())
                }),
            TreeViewNode(
                label = "📁 Library Path",
                collapsibleState = TreeItemCollapsibleState.Expanded,
                children = project!!.projectPaths.libraryPath.map {
                    TreeViewNode(it.toString())
                }),
            TreeViewNode(
                label = "📁 Class Path",
                collapsibleState = TreeItemCollapsibleState.Expanded,
                children = project!!.projectPaths.classPath.map {
                    TreeViewNode(it.toString())
                })
        )))

        client?.setStatusBarMessage(StatusMessage("Loading configuration..."))

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

private fun createServerLauncher(server: LanguageServer, `in`: InputStream, out: OutputStream) =
    LSPLauncher.Builder<CryptoLanguageClient>()
        .setLocalService(server)
        .setRemoteInterface(CryptoLanguageClient::class.java)
        .setInput(`in`)
        .setOutput(out)!!

private fun createServerLauncher(server: LanguageServer, `in`: InputStream, out: OutputStream, validate: Boolean, trace: PrintWriter) =
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