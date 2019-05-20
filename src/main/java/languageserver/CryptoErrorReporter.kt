package languageserver

import boomerang.jimple.Statement
import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.pathconditions.expressions.ContextFormat
import crypto.pathconditions.expressions.JFalse
import crypto.pathconditions.expressions.JTrue
import crypto.reporting.PathConditionsErrorMarkerListener
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location

data class DataFlowPathItem(val location: Location, val statement: Statement)

data class PathConditionItem(
    val conditionAsString: String,
    val branchLocations: Set<Location>,
    val flowAnalysisVisualization: String
)

sealed class PathConditionsInfo
data class PathConditionsSuccess(val items: List<PathConditionItem>) : PathConditionsInfo()
data class PathConditionsError(val exception: Exception) : PathConditionsInfo()

data class CogniCryptDiagnostic(
    val id: String,
    val summary: String,
    val message: String,
    val methodName: String,
    val className: String,
    val severity: DiagnosticSeverity,
    val location: Location?,
    val pathConditions: PathConditionsInfo,
    val dataFlowPath: List<DataFlowPathItem>)

class CryptoErrorReporter : PathConditionsErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun afterAnalysis() {
        diagnostics = this.errorMarkers.rowMap()
            .flatMap { klassMap ->
                klassMap.value.flatMap { methodMap ->
                    methodMap.value.map { error ->
                        val stmt = error.errorLocation.unit.get()

                        val summary = "${error.javaClass.simpleName}: ${error.toErrorMarkerString()}"

                        val message = String.format("%s violating CrySL rule for %s:\n%s",
                            error.javaClass.simpleName,
                            error.rule.className,
                            error.toErrorMarkerString())

                        val (pathConditions, severity) =
                            try {
                                val computedPathConditions = when (error) {
                                    is ErrorWithObjectAllocation -> error.computePathConditions()
                                    else -> emptySet()
                                }

                                val severity = when {
                                    computedPathConditions.isEmpty() -> DiagnosticSeverity.Error
                                    computedPathConditions.all { it.condition == JFalse } -> DiagnosticSeverity.Information
                                    computedPathConditions.all { it.condition == JTrue } -> DiagnosticSeverity.Error
                                    else -> DiagnosticSeverity.Warning
                                }

                                val pathConditions = computedPathConditions
                                    .map {
                                        PathConditionItem(
                                            it.condition.prettyPrint(ContextFormat.ContextFree),
                                            it.branchStatements
                                                .mapNotNull { ifStmt -> ifStmt.unit.get().sourceLocation }
                                                .toSet(),
                                            it.asDirectedGraph().toDotString())
                                    }

                                PathConditionsSuccess(pathConditions) to severity
                            } catch (ex: Exception) {
                                PathConditionsError(ex) to DiagnosticSeverity.Error
                            }

                        val dataFlowPath = when (error) {
                            is ErrorWithObjectAllocation -> error.dataFlowPath
                                .mapNotNull {
                                    it.stmt().unit.get().sourceLocation?.let { location ->
                                        DataFlowPathItem(location, it.stmt())
                                    }
                                }
                                .toList()
                            else -> emptyList()
                        }

                        // This should be (1) unique among all diagnostics, and (2) persistent across analysis runs
                        val id = message + error.errorLocation.unit.get().sourceLocation?.toString()

                        CogniCryptDiagnostic(
                            id,
                            summary,
                            message,
                            error.errorLocation.method.name,
                            error.errorLocation.method.declaringClass.name,
                            severity,
                            stmt.sourceLocation,
                            pathConditions,
                            dataFlowPath)
                    }
                }
            }
            .toList()
    }
}