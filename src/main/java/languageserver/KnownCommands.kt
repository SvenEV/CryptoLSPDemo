package languageserver

import org.eclipse.lsp4j.Command

enum class KnownCommands(val id: String, val title: String) {
    Reanalyze("reanalyze", "Re-Analyze"),
    Debug("lspdebug", "LSP Debug"),
    ShowCfg("showcfg", "Show CFG");

    val asCommand get() = Command(title, id)
    fun asCommand(vararg arguments: Any) = Command(title, id, arguments.toList())

    companion object {
        fun tryParse(s: String) = KnownCommands.values().firstOrNull { it.id == s }
    }
}