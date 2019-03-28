import java.nio.file.Path

data class CryptoTextDocumentState(val sourcePath: Path)

/** Keeps track of files currently opened in the client */
class ServerDocumentStore(val rootFolder: Path) {
    private val documentState = mutableMapOf<Path, CryptoTextDocumentState>()

    fun add(path: Path) {
        documentState[path] = CryptoTextDocumentState(path)
    }

    fun remove(path: Path) =
        documentState.remove(path)

    val documents get() = documentState.values.toList()
    fun tryGet(path: Path) = documentState[path]
}