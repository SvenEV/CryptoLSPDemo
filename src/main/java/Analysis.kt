import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import org.apache.commons.lang3.exception.ExceptionUtils
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.nio.file.Path

fun analyze(client: LanguageClient?, rulesDir: String, rootFolder: Path): Collection<CogniCryptDiagnostic> =
    try {
        val transformer = CryptoTransformer(rulesDir)
        loadSourceCode(rootFolder)
        runSootPacks(transformer)
        transformer.diagnostics
    } catch (e: Exception) {
        val trace = ExceptionUtils.getStackTrace(e)
        client?.showMessage(MessageParams(MessageType.Error, "Analysis failed:\n$trace"))
        emptyList()
    }

private fun loadSourceCode(rootFolder: Path) {
    val loader = WalaClassLoader(rootFolder.toString())
    val sootClasses = loader.sootClasses
    val jimpleConverter = JimpleConverter(sootClasses)
    jimpleConverter.convertAllClasses()
}

private fun runSootPacks(t: Transformer) {
    PackManager.v().getPack("wjtp").add(Transform("wjtp.cognicrypt", t))
    PackManager.v().getPack("cg").apply()
    PackManager.v().getPack("wjtp").apply()
}