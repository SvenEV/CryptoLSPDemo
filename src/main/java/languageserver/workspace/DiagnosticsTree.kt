package languageserver.workspace

import crypto.pathconditions.debug.prettyPrintRefined
import languageserver.*

object DiagnosticsTree {
    fun buildTree(diagnostics: Iterable<CogniCryptDiagnostic>) =
        diagnostics
            .groupBy { it.location?.uri }
            .map { (uri, diags) ->
                TreeViewNode(
                    resourceUri = uri,
                    label = if (uri == null) "<Project>" else null,
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = diags.map { diag ->
                        TreeViewNode(
                            id = diag.id.toString(),
                            label = "🔸 ${diag.summary}",
                            tooltip = diag.message,
                            collapsibleState = TreeItemCollapsibleState.Collapsed,
                            children = listOf(
                                TreeViewNode(
                                    id = "${diag.id}/dataFlowPath",
                                    label = "🏁 Data Flow Path",
                                    command = KnownCommands.GoToStatement.asCommand(diag.dataFlowPath.map { it.location }),
                                    collapsibleState = TreeItemCollapsibleState.Collapsed,
                                    children = diag.dataFlowPath.mapIndexed { i, entry ->
                                        TreeViewNode(
                                            id = "${diag.id}/dataFlowPath/$i",
                                            label = "◼ ${readRangeFromFile(entry.location, true)
                                                ?: entry.statement.prettyPrintRefined()}",
                                            command = KnownCommands.GoToStatement.asCommand(listOf(entry.location)))
                                    }),
                                TreeViewNode(
                                    id = "${diag.id}/pathConditions",
                                    label = "💡 Path Conditions",
                                    collapsibleState = TreeItemCollapsibleState.Collapsed,
                                    children = diag.pathConditions.mapIndexed { i, entry ->
                                        TreeViewNode(
                                            id = "${diag.id}/pathConditions/$i",
                                            label = entry.conditionAsString,
                                            command = KnownCommands.GoToStatement.asCommand(entry.branchLocations),
                                            data = entry.flowAnalysisVisualization,
                                            contextValue = "pathCondition")
                                    })
                            )
                        )
                    }
                )
            }
}