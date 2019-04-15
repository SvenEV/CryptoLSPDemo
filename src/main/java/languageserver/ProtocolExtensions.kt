package languageserver

import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Range

// "cognicrypt/showCFG"

data class ShowCfgParams(val dotString: String)

// "cognicrypt/status"

data class StatusMessage(
    val text: String,
    val details: String? = null
)

// "cognicrypt/treeData"

data class PublishTreeDataParams(
    val viewId: String,
    val rootItems: List<TreeViewNode>,
    val focus: Boolean = false
)

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

// "cognicrypt/quickPick"

data class QuickPickParams(
    val items: List<String>,
    val placeHolder: String?
)

data class QuickPickResult(
    val selectedItem: String?
)

// "cognicrypt/showTextDocument"

data class ShowTextDocumentParams(
    val content: String,
    val language: String,
    val selection: Range? = null
)