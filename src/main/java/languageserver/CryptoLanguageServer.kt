package languageserver

import languageserver.workspace.AnalysisResults
import languageserver.workspace.DiagnosticsTree
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
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import java.nio.file.Paths

class CryptoLanguageServer(private val rulesDir: String) : LanguageServer, LanguageClientAware {
    override fun shutdown() = CompletableFuture.completedFuture<Any>(null)!!

    override fun exit() {}

    override fun connect(client: LanguageClient) {
        this.client = client as CryptoLanguageClient
    }

    fun connect(client: CryptoLanguageClient, socket: Socket) {
        connect(client)
        connectionSocket = socket
    }

    /** Keeps track of all documents belonging to the workspace currently opened in the client */
    lateinit var documentStore: ServerDocumentStore

    val logger = Logger()
    lateinit var client: CryptoLanguageClient
    var initParams = FutureValue<InitializeParams>()
    var connectionSocket: Socket? = null
    var project = FutureValue<WorkspaceProject>() // represents the single workspace root folder opened in the editor (multi-root workspaces are not yet supported)
    var configuration = defaultConfiguration

    suspend fun notifyStaleResults(msg: String) {
        when (configuration.autoReanalyze) {
            AutoReanalyze.Never -> {
            } // Nothing to do
            AutoReanalyze.Always -> performAnalysis()
            AutoReanalyze.AskEveryTime -> {
                val params = ShowMessageRequestParams().apply {
                    type = MessageType.Info
                    message = "$msg, re-analyze? (You can change this behavior in the settings.)"
                    actions = listOf(MessageActionItem("Re-Analyze"))
                }
                val action = client.showMessageRequest(params)?.await()
                if (action?.title == "Re-Analyze")
                    performAnalysis()
            }
        }
    }

    fun notifyMultiWorkspaceNotSupported() {
        client.showMessage(MessageParams(MessageType.Warning, "Multiple workspace folders are not supported by the CogniCrypt language server"))
    }

    /** Removes all diagnostics for a certain file from the language client. Analysis results remain unchanged. */
    suspend fun clearDiagnosticsForFile(filePath: Path) {
        val currentDiags = project.getAsync().analysisResults.diagnostics
        if (currentDiags.any { it.location?.uri?.asFilePath == filePath }) {
            val publishParams = PublishDiagnosticsParams().apply {
                uri = filePath.toUri().toString()
                diagnostics = emptyList()
            }
            client.publishDiagnostics(publishParams)
        } else {
            // No diagnostics exist for that file, no need to notify client
        }
    }

    suspend fun performAnalysis() {
        fun publishDiagnostics(diagnostics: Collection<CogniCryptDiagnostic>) {
            diagnostics
                .filter { result -> result.location != null }
                .map { result ->
                    Diagnostic().apply {
                        source = result.location!!.uri
                        message = result.message
                        range = result.location.range
                        severity = result.severity
                        relatedInformation = when (result.pathConditions) {
                            is PathConditionsError -> emptyList()
                            is PathConditionsSuccess -> result.pathConditions.items.map {
                                DiagnosticRelatedInformation(result.location, it.conditionAsString)
                            }
                        }
                    }
                }
                .groupBy { it.source }
                .forEach { (sourceUri, diags) ->
                    val publishParams = PublishDiagnosticsParams().apply {
                        this.uri = sourceUri
                        this.diagnostics = diags
                    }
                    client.publishDiagnostics(publishParams)
                }

            val tree = DiagnosticsTree.buildTree(diagnostics)
            client.publishTreeData(PublishTreeDataParams("cognicrypt.diagnostics", tree))
        }

        val result = analyze(client, rulesDir, project.getAsync(), configuration.codeSource) ?: return
        publishDiagnostics(result.diagnostics)

        val methodCodeLenses = soot.Scene.v().classes
            .flatMap { klass ->
                klass.methods.mapNotNull { method ->
                    val location = method.sourceLocation
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

        project.getAsync().updateAnalysisResults(AnalysisResults(result.diagnostics, methodCodeLenses, result.icfg!!))
        client.setStatusBarMessage(null)
    }

    override fun getTextDocumentService() = CryptoTextDocumentService(this)

    override fun getWorkspaceService(): WorkspaceService = CryptoWorkspaceService(this)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.logClientMsg(params.toString())

        initParams.complete(params)
        documentStore = ServerDocumentStore()

        // Report server capabilities
        val result = InitializeResult(
            ServerCapabilities().apply {
                setTextDocumentSync(TextDocumentSyncKind.Full)
                documentHighlightProvider = true
                codeLensProvider = CodeLensOptions().apply { isResolveProvider = true }
                executeCommandProvider = ExecuteCommandOptions(KnownCommands.values()
                    .filter { it.commandHandlerSite == CommandHandlerSite.Server }
                    .map { it.id })
                workspace = WorkspaceServerCapabilities(WorkspaceFoldersOptions().apply {
                    setChangeNotifications(true)
                })
            })

        logger.logServerMsg(result.toString())
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized(params: InitializedParams?) {
        GlobalScope.future {
            super.initialized(params)

            // Load configuration
            client.setStatusBarMessage(StatusMessage("Loading configuration..."))
            configuration = requestConfiguration(client, initParams.getAsync().rootUri).await()

            client.setStatusBarMessage(StatusMessage("Initializing project..."))

            val proj = when (configuration.codeSource) {
                CodeSource.Source -> {
                    WorkspaceProject.create(initParams.getAsync().rootUri.asFilePath)
                }
                CodeSource.Compiled -> {
                    val result = client.connectToJavaExtension().await()
                    WorkspaceProject.create(Paths.get(result.jdtWorkspacePath))
                }
            }

            client.publishTreeData(PublishTreeDataParams("cognicrypt.info", listOf(
                TreeViewNode(
                    label = "📁 Source Path",
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = proj.projectPaths.sourcePath.map {
                        TreeViewNode(it.toString())
                    }),
                TreeViewNode(
                    label = "📁 Library Path",
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = proj.projectPaths.libraryPath.map {
                        TreeViewNode(it.toString())
                    }),
                TreeViewNode(
                    label = "📁 Class Path",
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = proj.projectPaths.classPath.map {
                        TreeViewNode(it.toString())
                    })
            )))

            project.complete(proj)
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
        ServerSocket(port).use { serverSocket ->
            serverSocket.accept().use { connectionSocket ->
                val launcher = createServerLauncher(this,
                    connectionSocket.getInputStream().logStream(),//"magpie.in"),
                    connectionSocket.getOutputStream().logStream()//"magpie.out")
                ).create()
                connect(launcher.remoteProxy, connectionSocket)
                launcher.startListening().get()
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}