package languageserver.workspace

import crypto.pathconditions.debug.prettyPrintRefined
import languageserver.*

object DiagnosticsTree {
    fun buildTree(diagnostics: Iterable<CogniCryptDiagnostic>) =
        diagnostics
            .groupBy { it.location.uri }
            .map { (uri, diags) ->
                TreeViewNode(
                    resourceUri = uri,
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = diags.map { diag ->
                        TreeViewNode(
                            label = "🔸 ${diag.summary}",
                            tooltip = diag.message,
                            collapsibleState = TreeItemCollapsibleState.Collapsed,
                            children = listOf(
                                TreeViewNode(
                                    label = "🏁 Data Flow Path",
                                    collapsibleState = TreeItemCollapsibleState.Collapsed,
                                    children = diag.dataFlowPath.map {
                                        TreeViewNode(
                                            label = "◼ ${it.statement.prettyPrintRefined()}",
                                            command = KnownCommands.GoToStatement.asCommand(it.location))
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
                )
            }
}