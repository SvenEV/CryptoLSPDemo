import magpiebridge.core.AnalysisResult
import magpiebridge.core.MagpieServer
import magpiebridge.core.Utils
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.PublishDiagnosticsParams
import java.util.function.Consumer

class CryptoLanguageServer(private val rulesDir: String) : MagpieServer() {

    /** Keeps track of all documents currently opened in the client */
    val documentStore = ServerDocumentStore()

    override fun getTextDocumentService() = CryptoTextDocumentService(this, { client }, rulesDir)

    override fun createDiagnosticConsumer(diagList: MutableList<Diagnostic>, source: String) = Consumer<AnalysisResult> { result ->
        val diag = Diagnostic().apply {
            this.source = source
            message = result.toString(false)
            range = Utils.getLocationFrom(result.position()).range
            severity = result.severity()
            relatedInformation = result.related().map { related ->
                DiagnosticRelatedInformation().apply {
                    location = Utils.getLocationFrom(related.fst)
                    message = related.snd
                }
            }
        }

        if (!diagList.contains(diag)) {
            diagList.add(diag)
        }

        val serverUri = result.position().url.toStringWithWindowsFix()
        val publishParams = PublishDiagnosticsParams().apply {
            diagnostics = diagList
            uri = documentStore.getByServerUri(serverUri)!!.clientUri
        }

        client.publishDiagnostics(publishParams)
        logger.logServerMsg(publishParams.toString())
        System.err.println("server:\n$publishParams")
    }
}
