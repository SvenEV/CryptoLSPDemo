package languageserver.workspace

import crypto.pathconditions.debug.prettyPrintRefined
import languageserver.*
import org.eclipse.lsp4j.DiagnosticSeverity

object DiagnosticsTree {

    private fun buildTreeForMethod(methodId: String, diags: List<CogniCryptDiagnostic>) =
        diags.sortedBy { it.id }.map { diag ->
            // Diagnostic node
            val diagId = "$methodId/${diag.id}"
            val icon = when (diag.severity) {
                DiagnosticSeverity.Error -> "🚩"
                DiagnosticSeverity.Warning -> ""
                else -> ""
            }
            TreeViewNode(
                id = diagId,
                label = "$icon ${diag.summary}",
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
                                collapsibleState = TreeItemCollapsibleState.Expanded,
                                children = buildTreeForMethod(methodId, diags)
                            )
                        })
            }

    /**
     * Constructs the tree for a subset of all diagnostics for the purpose of filtering on a specific method.
     */
    fun buildFilteredTree(diagnostics: List<CogniCryptDiagnostic>): List<TreeViewNode> {
        val className = diagnostics.first().className
        val methodName = diagnostics.first().methodName
        val methodId = "$className/$methodName"
        val filterHeader = listOf(
            TreeViewNode("Diagnostics in method:"),
            TreeViewNode("⚙ $className.$methodName"),
            TreeViewNode("❌ Clear Filter", command = KnownCommands.FilterDiagnostics.asCommand()),
            TreeViewNode("————————")
        )
        return filterHeader + buildTreeForMethod(methodId, diagnostics)
    }
}