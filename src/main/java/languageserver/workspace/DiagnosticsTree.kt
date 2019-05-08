package languageserver.workspace

import crypto.pathconditions.debug.prettyPrintRefined
import languageserver.*

object DiagnosticsTree {
    fun buildTree(diagnostics: Iterable<CogniCryptDiagnostic>) =
        diagnostics
            .groupBy { it.location?.uri }
            .map { (uri, diags) ->
                // For each source file...
                TreeViewNode(
                    resourceUri = uri,
                    label = if (uri == null) "<Project>" else null,
                    collapsibleState = TreeItemCollapsibleState.Expanded,
                    children = diags
                        .groupBy { it.className to it.methodName }
                        .toList()
                        .sortedBy { (name, _) -> name.second }
                        .map { (name, diags) ->
                            // For each class and method...
                            val (className, methodName) = name
                            val methodId = "$className/$methodName"
                            TreeViewNode(
                                id = methodId,
                                label = "⚙ $methodName",
                                collapsibleState = TreeItemCollapsibleState.Collapsed,
                                children = diags.sortedBy { it.id }.map { diag ->
                                    // Diagnostic node
                                    val diagId = "$methodId/$diag.id"
                                    TreeViewNode(
                                        id = diagId,
                                        label = "🔴 ${diag.summary}",
                                        tooltip = diag.message,
                                        collapsibleState = TreeItemCollapsibleState.Collapsed,
                                        children = listOf(
                                            TreeViewNode(
                                                id = "$diagId/dataFlowPath",
                                                label = "🏁 Data Flow Path",
                                                command = KnownCommands.GoToStatement.asCommand(diag.dataFlowPath.map { it.location }),
                                                collapsibleState = TreeItemCollapsibleState.Collapsed,
                                                children = diag.dataFlowPath.mapIndexed { i, entry ->
                                                    TreeViewNode(
                                                        id = "$diagId/dataFlowPath/$i",
                                                        label = "◼ ${readRangeFromFile(entry.location, true)
                                                            ?: entry.statement.prettyPrintRefined()}",
                                                        command = KnownCommands.GoToStatement.asCommand(listOf(entry.location)))
                                                }),
                                            TreeViewNode(
                                                id = "$diagId/pathConditions",
                                                label = "💡 Path Conditions",
                                                collapsibleState = TreeItemCollapsibleState.Collapsed,
                                                children = diag.pathConditions.mapIndexed { i, entry ->
                                                    TreeViewNode(
                                                        id = "$diagId/pathConditions/$i",
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
                )
            }
}