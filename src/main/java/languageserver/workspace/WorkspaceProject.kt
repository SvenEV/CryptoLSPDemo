package languageserver.workspace

import languageserver.*
import languageserver.projectsystem.InferConfig
import languageserver.projectsystem.InferSourcePath
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Position
import soot.SootMethod
import soot.jimple.toolkits.ide.icfg.AbstractJimpleBasedICFG
import java.nio.file.Path

data class MethodCodeLens(val method: SootMethod, val codeLens: CodeLens)

data class AnalysisResults(
    val diagnostics: Collection<CogniCryptDiagnostic>,
    val methodCodeLenses: Map<Path, Collection<MethodCodeLens>>,
    val icfg: AbstractJimpleBasedICFG?
)

val defaultAnalysisResults = AnalysisResults(
    emptyList(),
    emptyMap(),
    null
)

data class ProjectPaths(
    val sourcePath: Set<Path>,
    val classPath: Set<Path>,
    val libraryPath: Set<Path>,
    val externalDependencies: Set<String>) {

    companion object {
        fun scan(rootPath: Path): ProjectPaths {
            val config = InferConfig(rootPath)
            return ProjectPaths(
                sourcePath = InferSourcePath.sourcePath(rootPath),
                classPath = config.classPath(),
                libraryPath = config.libraryClassPath(),
                externalDependencies = emptySet()
            )
        }
    }
}

/** Represents a root folder ("project") opened in the editor */
data class WorkspaceProject(
    val rootPath: Path,
    val projectPaths: ProjectPaths,
    var analysisResults: AnalysisResults,
    var analysisResultsAwaiter: FutureValue<AnalysisResults>) {

    fun diagnosticsAt(filePath: Path, position: Position) =
        analysisResults.diagnostics.filter {
            it.location?.uri?.asFilePath == filePath &&
                it.location.range.contains(position)
        }

    fun invalidateDiagnostics() {
        if (analysisResultsAwaiter.isDone)
            analysisResultsAwaiter = FutureValue()
    }

    fun updateAnalysisResults(results: AnalysisResults) {
        analysisResults = results
        analysisResultsAwaiter.complete(results)
    }

    companion object {
        fun create(rootPath: Path) = WorkspaceProject(
            rootPath,
            ProjectPaths.scan(rootPath),
            defaultAnalysisResults,
            FutureValue())
    }
}