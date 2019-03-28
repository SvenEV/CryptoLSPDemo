import org.eclipse.lsp4j.Command

enum class KnownCommands(val id: String, val title: String) {
    Debug("lspdebug", "LSP Debug");

    val asCommand get() = Command(title, id)

    companion object {
        fun tryParse(s: String) = KnownCommands.values().firstOrNull { it.id == s }
    }
}