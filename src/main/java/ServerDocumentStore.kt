import com.ibm.wala.classLoader.SourceFileModule
import com.ibm.wala.util.io.TemporaryFile
import java.io.File
import java.nio.file.Paths

data class CryptoTextDocumentState(val clientUri: String, val serverUri: String, val text: String, val module: SourceFileModule)

class ServerDocumentStore {
    private val documentState = mutableMapOf<String, CryptoTextDocumentState>()
    private val serverClientUri = mutableMapOf<String, String>()

    fun add(clientUri: String, text: String) {
        // Copy document to temporary file
        val file = File.createTempFile("temp", ".java").apply { deleteOnExit() }
        TemporaryFile.stringToFile(file, text)
        val module = SourceFileModule(file, clientUri, null)
        val serverUri = Paths.get(file.toURI()).toUri().toString()

        val state = CryptoTextDocumentState(clientUri, serverUri, text, module)
        documentState[clientUri] = state
        serverClientUri[serverUri] = clientUri
    }

    fun remove(clientUri: String) =
        documentState.remove(clientUri)?.let {
            serverClientUri.remove(it.serverUri)
            true
        } ?: false

    fun update(clientUri: String, newText: String): Boolean {
        val doc = documentState[clientUri]
        return if (doc == null) {
            false
        } else {
            // Update text in temporary file
            TemporaryFile.stringToFile(doc.module.file, newText)
            documentState[clientUri] = doc.copy(text = newText)
            true
        }
    }

    val documents get() = documentState.values.toList()
    fun getByClientUri(clientUri: String) = documentState[clientUri]
    fun getByServerUri(serverUri: String) = serverClientUri[serverUri]?.let { getByClientUri(it) }
}