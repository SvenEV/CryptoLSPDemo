package languageserver

import boomerang.jimple.Statement
import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.pathconditions.expressions.WithContextFormat
import crypto.reporting.PathConditionsErrorMarkerListener
import de.upb.soot.frontends.java.PositionTag
import org.eclipse.lsp4j.*

data class DataFlowPathItem(val location: Location, val statement: Statement)

data class CogniCryptDiagnostic(
    val summary: String,
    val message: String,
    val severity: DiagnosticSeverity,
    val location: Location,
    val pathConditions: List<DiagnosticRelatedInformation>,
    val dataFlowPath: List<DataFlowPathItem>)

class CryptoErrorReporter : PathConditionsErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    override fun afterAnalysis() {
        diagnostics = this.errorMarkers.rowMap()
            .flatMap { klassMap ->
                klassMap.value.flatMap { methodMap ->
                    methodMap.value.map { error ->
                        val stmt = error.errorLocation.unit.get()
                        val location = (stmt.getTag("PositionTag") as PositionTag).position.asLocation

                        val message = String.format("%s violating CrySL rule for %s:\n%s",
                            error.javaClass.simpleName,
                            error.rule.className,
                            error.toErrorMarkerString())

                        val pathConditions = when (error) {
                            is ErrorWithObjectAllocation -> error.pathConditions
                                .map {
                                    DiagnosticRelatedInformation(
                                        location,
                                        it.condition.prettyPrint(WithContextFormat.ContextFree)
                                    )
                                }
                            else -> emptyList()
                        }

                        val dataFlowPath = when (error) {
                            is ErrorWithObjectAllocation -> error.dataFlowPath
                                .mapNotNull {
                                    tryGetSourceLocation(it.stmt().unit.get())?.let { location ->
                                        DataFlowPathItem(location, it.stmt())
                                    }
                                }
                                .toList()
                            else -> emptyList()
                        }

                        CogniCryptDiagnostic(error.javaClass.simpleName, message, DiagnosticSeverity.Error, location, pathConditions, dataFlowPath)
                    }
                }
            }
            .toList()
    }
}