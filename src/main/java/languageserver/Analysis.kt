package languageserver

import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import languageserver.workspace.ProjectPaths
import org.apache.commons.lang3.exception.ExceptionUtils
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import soot.PackManager
import soot.Transform
import soot.Transformer
import java.lang.RuntimeException
import java.nio.file.Path

fun analyze(client: CryptoLanguageClient?, rulesDir: String, projectPaths: ProjectPaths): CryptoTransformer? =
    try {
        val transformer = CryptoTransformer(rulesDir)
        client?.setStatusBarMessage("Processing sources...")
        loadSourceCode(projectPaths)
        client?.setStatusBarMessage("CogniCrypt analysis...")
        runSootPacks(transformer)
        transformer
    } catch (e: Throwable) {
        val trace = ExceptionUtils.getStackTrace(e)
        client?.setStatusBarMessage("Analysis failed")
        client?.showMessage(MessageParams(MessageType.Error, "Analysis failed:\n$trace"))
        null
    }

private fun loadSourceCode(projectPaths: ProjectPaths) {
    val sourceDirs = projectPaths.sourcePath.map { it.toString() }.toSet()
    val libDirs = projectPaths.libraryPath.map { it.toString() }.toSet()
    val loader = WalaClassLoader(sourceDirs, libDirs, null)
    val sootClasses = loader.sootClasses
    val jimpleConverter = JimpleConverter(sootClasses)
    jimpleConverter.convertAllClasses()
}

private fun runSootPacks(t: Transformer) {
    PackManager.v().getPack("wjtp").add(Transform("wjtp.cognicrypt", t))
    PackManager.v().getPack("cg").apply()
    PackManager.v().getPack("wjtp").apply()
}