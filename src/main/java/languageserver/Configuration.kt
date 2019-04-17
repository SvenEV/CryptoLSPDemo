package languageserver

import com.google.gson.JsonObject
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams

enum class AutoReanalyze {
    Always, Never, AskEveryTime;

    companion object {
        fun parse(s: String) = when (s) {
            "always" -> Always
            "never" -> Never
            "ask" -> AskEveryTime
            else -> defaultConfiguration.autoReanalyze
        }
    }
}

enum class LspTransport {
    Stdio, Socket;

    companion object {
        fun parse(s: String) = when (s) {
            "stdio" -> Stdio
            "socket" -> Socket
            else -> defaultConfiguration.lspTransport
        }
    }
}

enum class CodeSource {
    Source, Compiled;

    companion object {
        fun parse(s: String) = when (s) {
            "source" -> Source
            "compiled" -> Compiled
            else -> defaultConfiguration.codeSource
        }
    }
}

data class Configuration(
    val autoReanalyze: AutoReanalyze,
    val lspTransport: LspTransport,
    val codeSource: CodeSource
)

val defaultConfiguration = Configuration(
    AutoReanalyze.AskEveryTime,
    LspTransport.Stdio,
    CodeSource.Source
)

fun requestConfiguration(client: CryptoLanguageClient, scope: String) = client
    .configuration(ConfigurationParams(listOf(
        ConfigurationItem().apply {
            scopeUri = scope
            section = "cognicrypt"
        }
    )))
    .thenApply { config -> configurationFromJson(config.first() as JsonObject) }!!

fun configurationFromJson(json: JsonObject): Configuration {
    return Configuration(
        autoReanalyze = AutoReanalyze.parse(json["autoReanalyze"].asString),
        lspTransport = LspTransport.parse(json["lspTransport"].asString),
        codeSource = CodeSource.parse(json["codeSource"].asString)
    )
}