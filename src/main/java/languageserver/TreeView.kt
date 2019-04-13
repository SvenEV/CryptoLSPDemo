package languageserver

import org.eclipse.lsp4j.Command
import java.net.URL

data class TreeViewNode(
    val label: String? = null,
    val resourceUri: String? = null,
    val children: List<TreeViewNode> = emptyList(),
    val tooltip: String? = null,
    val id: String? = null,
    val collapsibleState: TreeItemCollapsibleState = TreeItemCollapsibleState.None,
    val command: Command? = null
)

enum class TreeItemCollapsibleState {
    None, Collapsed, Expanded
}