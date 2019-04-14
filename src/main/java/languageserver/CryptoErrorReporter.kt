package languageserver

import boomerang.jimple.Statement
import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.pathconditions.expressions.ContextFormat
import crypto.pathconditions.ofType
import crypto.reporting.PathConditionsErrorMarkerListener
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location

data class DataFlowPathItem(val location: Location, val statement: Statement)

data class CogniCryptDiagnostic(
    val id: Int,
    val summary: String,
    val message: String,
    val severity: DiagnosticSeverity,
    val location: Location,
    val pathConditions: List<DiagnosticRelatedInformation>,
    val dataFlowPath: List<DataFlowPathItem>)

class CryptoErrorReporter : PathConditionsErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun afterAnalysis() {
        var id = 0
        diagnostics = this.errorMarkers.rowMap()
            .flatMap { klassMap ->
                klassMap.value.flatMap { methodMap ->
                    methodMap.value.map { error ->
                        val stmt = error.errorLocation.unit.get()

                        val message = String.format("%s violating CrySL rule for %s:\n%s",
                            error.javaClass.simpleName,
                            error.rule.className,
                            error.toErrorMarkerString())

                        val relatedErrors = methodMap.value
                            .filter { it.errorLocation.unit.get() == stmt }
                            .ofType<ErrorWithObjectAllocation>()

                        val pathConditions = when (error) {
                            is ErrorWithObjectAllocation -> error.getPathConditions(relatedErrors)
                                .map {
                                    DiagnosticRelatedInformation(
                                        stmt.sourceLocation,
                                        it.condition.prettyPrint(ContextFormat.ContextFree)
                                    )
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

                        CogniCryptDiagnostic(
                            id++,
                            error.javaClass.simpleName,
                            message,
                            DiagnosticSeverity.Error,
                            stmt.sourceLocation!!,
                            pathConditions,
                            dataFlowPath)
                    }
                }
            }
            .toList()
    }
}