package languageserver

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient

data class ShowCfgParams(val dotString: String)

interface CryptoLanguageClient : LanguageClient {
    @JsonNotification("cognicrypt/showCFG")
    fun showCfg(args: ShowCfgParams)

    @JsonNotification("cognicrypt/status")
    fun setStatusBarMessage(args: String)
}
