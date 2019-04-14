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

data class Configuration(
    val autoReanalyze: AutoReanalyze
)

val defaultConfiguration = Configuration(AutoReanalyze.AskEveryTime)

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
        autoReanalyze = AutoReanalyze.parse(json["autoReanalyze"].asString)
    )
}