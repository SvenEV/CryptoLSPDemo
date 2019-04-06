package languageserver.workspace

import de.upb.soot.Project
import languageserver.CogniCryptDiagnostic
import languageserver.asFilePath
import languageserver.contains
import languageserver.projectsystem.InferConfig
import languageserver.projectsystem.InferSourcePath
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import soot.SootMethod
import soot.jimple.toolkits.ide.icfg.AbstractJimpleBasedICFG
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

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
    var analysisResultsAwaiter: CompletableFuture<Unit>) {

    fun diagnosticsAt(filePath: Path, position: Position) =
        analysisResults.diagnostics.filter {
            it.location.uri.asFilePath == filePath &&
                it.location.range.contains(position)
        }

    fun invalidateDiagnostics() {
        if (analysisResultsAwaiter.isDone)
            analysisResultsAwaiter = CompletableFuture()
    }

    fun updateAnalysisResults(results: AnalysisResults) {
        analysisResults = results
        analysisResultsAwaiter.complete(Unit)
    }

    fun clearDiagnosticsForFile(filePath: Path): PublishDiagnosticsParams {
        val remainingDiagnostics = analysisResults.diagnostics.filter { it.location.uri.asFilePath != filePath }

        val publishParams = PublishDiagnosticsParams().apply {
            uri = filePath.toUri().toString()
            diagnostics = emptyList()
        }
        analysisResults = analysisResults.copy(diagnostics = remainingDiagnostics)
        System.err.println("server:\n$publishParams")
        return publishParams
    }

    companion object {
        fun create(rootPath: Path) = WorkspaceProject(
            rootPath,
            ProjectPaths.scan(rootPath),
            defaultAnalysisResults,
            CompletableFuture())
    }
}