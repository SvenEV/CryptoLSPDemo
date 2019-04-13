package languageserver

import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import languageserver.workspace.ProjectPaths
import org.apache.commons.lang3.exception.ExceptionUtils
import soot.PackManager
import soot.Transform
import soot.Transformer

fun analyze(client: CryptoLanguageClient?, rulesDir: String, projectPaths: ProjectPaths): CryptoTransformer? =
    try {
        val transformer = CryptoTransformer(rulesDir)
        client?.setStatusBarMessage(StatusMessage("Processing sources..."))
        loadSourceCode(projectPaths)
        client?.setStatusBarMessage(StatusMessage("CogniCrypt analysis..."))
        runSootPacks(transformer)
        transformer
    } catch (e: Throwable) {
        val trace = ExceptionUtils.getStackTrace(e)
        client?.setStatusBarMessage(StatusMessage("Analysis failed, click for details", "# Exception\n```\n$trace\n```"))
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