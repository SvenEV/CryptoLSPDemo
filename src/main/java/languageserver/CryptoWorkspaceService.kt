package languageserver

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import crypto.pathconditions.debug.prettyPrint
import crypto.pathconditions.graphviz.toDotString
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import languageserver.workspace.DiagnosticsTree
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService
import soot.Scene
import java.util.concurrent.CompletableFuture

class CryptoWorkspaceService(private val server: CryptoLanguageServer) : WorkspaceService {
    private val client get() = server.client

    override fun didChangeWatchedFiles(args: DidChangeWatchedFilesParams) {
        GlobalScope.launch {
            args.changes.forEach {
                when (it.type!!) {
                    FileChangeType.Created -> {
                    }
                    FileChangeType.Changed -> server.clearDiagnosticsForFile(it.uri.asFilePath)
                    FileChangeType.Deleted -> server.clearDiagnosticsForFile(it.uri.asFilePath)
                }
            }
            server.project.getAsync().invalidateDiagnostics()
            server.notifyStaleResults("Files changed")
        }
    }


    override fun didChangeConfiguration(args: DidChangeConfigurationParams) {
        server.configuration = configurationFromJson((args.settings as JsonObject)["cognicrypt"].asJsonObject)
    }

    override fun didChangeWorkspaceFolders(args: DidChangeWorkspaceFoldersParams) {
        server.notifyMultiWorkspaceNotSupported()
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> = GlobalScope.future {
        when (KnownCommands.tryParse(params.command)) {
            KnownCommands.Reanalyze -> {
                server.project.getAsync().invalidateDiagnostics()
                server.performAnalysis()
            }

            KnownCommands.ShowCfg -> {
                val methodSignature = (params.arguments.firstOrNull() as? JsonPrimitive)?.asString
                val analysisResults = server.project.getAsync().analysisResults
                val method = analysisResults.methodCodeLenses
                    .flatMap { it.value }
                    .firstOrNull { it.method.signature == methodSignature }
                    ?.method

                if (method != null && analysisResults.icfg != null) {
                    val dotString = analysisResults.icfg.toDotString(method)
                    client.showTextDocument(ShowTextDocumentParams(dotString, "dot"))
                } else {
                    client.showMessage(MessageParams(MessageType.Error, "Didn't find method"))
                }
            }

            KnownCommands.VisualizeFlowAnalysis -> {
                val dotString = (params.arguments[0] as JsonObject)["data"].asString
                client.showTextDocument(ShowTextDocumentParams(dotString, "dot"))
            }

            KnownCommands.FilterDiagnostics -> {
                val diagnostics = server.project.getAsync().analysisResults.diagnostics
                val tree =
                    if (params.arguments.any()) {
                        val args = Gson().fromJson(params.arguments.firstOrNull() as JsonObject, DiagnosticCodeLens::class.java)
                        val diagnosticsFiltered = diagnostics.filter { it.id in args.diagnosticIds }
                        DiagnosticsTree.buildFilteredTree(diagnosticsFiltered, args.fileUri.asFilePath.fileName.toString(), args.lineNumber)
                    } else {
                        DiagnosticsTree.buildTree(diagnostics)
                    }
                client.publishTreeData(PublishTreeDataParams("cognicrypt.diagnostics", tree, focus = true))
            }

            KnownCommands.InspectJimple -> {
                val availableClasses = Scene.v().classes.map { it.name }
                client.quickPick(QuickPickParams(availableClasses, "Show Jimple code of class...")).thenAccept { result ->
                    val selectedClass = Scene.v().getSootClass(result.selectedItem)
                    if (selectedClass != null) {
                        client.showTextDocument(ShowTextDocumentParams(
                            content = selectedClass.prettyPrint(),
                            language = "java"
                        ))
                    }
                }
            }

            KnownCommands.ShowTextDocument -> {
                val args = Gson().fromJson(params.arguments[0] as? JsonObject, ShowTextDocumentParams::class.java)
                client.showTextDocument(args)
            }

            KnownCommands.GoToStatement -> {
            } // handled client-side
            null -> TODO()
        }

        0 // we must return something
    }
}
