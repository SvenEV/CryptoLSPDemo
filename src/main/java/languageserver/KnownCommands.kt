package languageserver

import org.eclipse.lsp4j.Command

enum class CommandHandlerSite { Client, Server }

enum class KnownCommands(val id: String, val title: String, val commandHandlerSite: CommandHandlerSite) {
    // Handled by language server:
    Reanalyze("cognicrypt.reanalyze", "Re-Analyze", CommandHandlerSite.Server),
    ShowCfg("cognicrypt.showCfg", "Show CFG", CommandHandlerSite.Server),
    VisualizeFlowAnalysis("cognicrypt.visualizeFlowAnalysis", "Visualize Flow Analysis", CommandHandlerSite.Server),
    FilterDiagnostics("cognicrypt.filterDiagnostics", "Filter Diagnostics", CommandHandlerSite.Server),
    InspectJimple("cognicrypt.inspectJimple", "Inspect Jimple", CommandHandlerSite.Server),
    ShowTextDocument("cognicrypt.showTextDocument", "Show Text Document", CommandHandlerSite.Server),

    // Handled by language client:
    GoToStatement("cognicrypt.goto", "Go To Statement", CommandHandlerSite.Client);

    val asCommand get() = Command(title, id)
    fun asCommand(vararg arguments: Any) = Command(title, id, arguments.toList())
    fun asCommandWithTitle(title: String, vararg arguments: Any) = Command(title, id, arguments.toList())

    companion object {
        fun tryParse(s: String) = values().firstOrNull { it.id == s }
    }
}