package languageserver

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import crypto.pathconditions.debug.prettyPrint
import crypto.pathconditions.graphviz.toDotString
import javafx.scene.Scene
import languageserver.workspace.DiagnosticsTree
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class CryptoWorkspaceService(
    private val server: CryptoLanguageServer,
    private val client: () -> CryptoLanguageClient?) : WorkspaceService {

    override fun didChangeWatchedFiles(args: DidChangeWatchedFilesParams) {
        server.project?.invalidateDiagnostics()
        args.changes.forEach {
            when (it.type!!) {
                FileChangeType.Created -> {}
                FileChangeType.Changed -> server.clearDiagnosticsForFile(it.uri.asFilePath)
                FileChangeType.Deleted -> server.clearDiagnosticsForFile(it.uri.asFilePath)
            }
        }
        server.notifyStaleResults("Files changed")
    }

    override fun didChangeConfiguration(args: DidChangeConfigurationParams) {
        server.configuration = configurationFromJson((args.settings as JsonObject)["cognicrypt"].asJsonObject)
    }

    override fun didChangeWorkspaceFolders(args: DidChangeWorkspaceFoldersParams) {
        server.notifyMultiWorkspaceNotSupported()
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        when (KnownCommands.tryParse(params.command)) {
            KnownCommands.Reanalyze -> {
                server.project?.invalidateDiagnostics()
                server.performAnalysis()
            }

            KnownCommands.ShowCfg -> {
                val methodSignature = (params.arguments.firstOrNull() as? JsonPrimitive)?.asString
                val analysisResults = server.project!!.analysisResults
                val method = analysisResults.methodCodeLenses
                    .flatMap { it.value }
                    .firstOrNull { it.method.signature == methodSignature }
                    ?.method

                if (method != null && analysisResults.icfg != null) {
                    val dotString = analysisResults.icfg.toDotString(method)
                    client()?.showCfg(ShowCfgParams(dotString))
                } else {
                    client()?.showMessage(MessageParams(MessageType.Error, "Didn't find method"))
                }
            }

            KnownCommands.FilterDiagnostics -> {
                val ids = (params.arguments.firstOrNull() as? JsonArray)?.map { it.asNumber.toInt() } ?: emptyList()
                val tree =
                    if (ids.any()) {
                        val diagnosticsFiltered = server.project!!.analysisResults.diagnostics.filter { it.id in ids }
                        val filterHeader = TreeViewNode("❌ Clear Filter", command = KnownCommands.FilterDiagnostics.asCommand())
                        listOf(filterHeader) + DiagnosticsTree.buildTree(diagnosticsFiltered)
                    } else {
                        DiagnosticsTree.buildTree(server.project!!.analysisResults.diagnostics)
                    }
                client()?.publishTreeData(PublishTreeDataParams("cognicrypt.diagnostics", tree, focus = true))
            }

            KnownCommands.InspectJimple -> {
                val availableClasses = soot.Scene.v().classes.map { it.name }
                client()?.quickPick(QuickPickParams(availableClasses, "Show Jimple code of class..."))?.thenAccept { result ->
                    val selectedClass = soot.Scene.v().getSootClass(result.selectedItem)
                    if (selectedClass != null) {
                        client()?.showTextDocument(ShowTextDocumentParams(
                            content = selectedClass.prettyPrint(),
                            language = "java"
                        ))
                    }
                }
            }

            null -> TODO()
        }
        return CompletableFuture.completedFuture(null)
    }
}
