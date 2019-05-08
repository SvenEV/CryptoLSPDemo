package languageserver

import boomerang.jimple.Statement
import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.pathconditions.expressions.ContextFormat
import crypto.reporting.PathConditionsErrorMarkerListener
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location

data class DataFlowPathItem(val location: Location, val statement: Statement)

data class PathConditionItem(
    val conditionAsString: String,
    val branchLocations: Set<Location>,
    val flowAnalysisVisualization: String
)

data class CogniCryptDiagnostic(
    val id: String,
    val summary: String,
    val message: String,
    val methodName: String,
    val className: String,
    val severity: DiagnosticSeverity,
    val location: Location?,
    val pathConditions: List<PathConditionItem>,
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

                        val pathConditions = when (error) {
                            is ErrorWithObjectAllocation -> error.computePathConditions()
                                .map {
                                    PathConditionItem(
                                        it.condition.prettyPrint(ContextFormat.ContextFree),
                                        it.branchStatements
                                            .mapNotNull { ifStmt -> ifStmt.unit.get().sourceLocation }
                                            .toSet(),
                                        it.asDirectedGraph().toDotString())
                                }
                            else -> emptyList()
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
                            DiagnosticSeverity.Error,
                            stmt.sourceLocation,
                            pathConditions,
                            dataFlowPath)
                    }
                }
            }
            .toList()
    }
}