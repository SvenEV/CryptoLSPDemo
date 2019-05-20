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
                DiagnosticSeverity.Error -> "StatusCriticalError_16x.svg"
                DiagnosticSeverity.Warning -> "StatusWarning_16x.svg"
                else -> "StatusInformation_16x.svg"
            }
            TreeViewNode(
                id = diagId,
                label = diag.summary,
                iconPath = "~/resources/icons/$icon",
                tooltip = diag.message,
                collapsibleState = TreeItemCollapsibleState.Collapsed,
                children = listOf(
                    TreeViewNode(
                        id = "$diagId/dataFlowPath",
                        label = "Data Flow Path",
                        iconPath = "~/resources/icons/ListMembers_16x.svg",
                        command = KnownCommands.GoToStatement.asCommand(diag.dataFlowPath.map { it.location }),
                        collapsibleState = TreeItemCollapsibleState.Collapsed,
                        children = diag.dataFlowPath.mapIndexed { i, entry ->
                            TreeViewNode(
                                id = "$diagId/dataFlowPath/$i",
                                label = readRangeFromFile(entry.location, true) ?: entry.statement.prettyPrintRefined(),
                                iconPath = "~/resources/icons/Bullet_16x.svg",
                                command = KnownCommands.GoToStatement.asCommand(listOf(entry.location)))
                        }),
                    TreeViewNode(
                        id = "$diagId/pathConditions",
                        label = "Path Conditions",
                        iconPath = "~/resources/icons/Lightbulb_16x.svg",
                        collapsibleState = TreeItemCollapsibleState.Collapsed,
                        children = diag.pathConditions.mapIndexed { i, entry ->
                            TreeViewNode(
                                id = "$diagId/pathConditions/$i",
                                label = entry.conditionAsString,
                                iconPath = "~/resources/icons/Bullet_16x.svg",
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
                                label = methodName,
                                iconPath = "~/resources/icons/Method_16x.svg",
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
        val filterHeader = listOf(
            TreeViewNode("Diagnostics in method:"),
            TreeViewNode(
                label = "$className.$methodName",
                iconPath = "~/resources/icons/Method_16x.svg"),
            TreeViewNode(
                label = "Clear Filter",
                command = KnownCommands.FilterDiagnostics.asCommand(),
                iconPath = "~/resources/icons/DeleteFilter_16x.svg"),
            TreeViewNode("————————")
        )
        return filterHeader + buildTreeForMethod("", diagnostics)
    }
}