import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.reporting.PathConditionsErrorMarkerListener
import de.upb.soot.frontends.java.PositionTag
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import soot.Unit
import soot.tagkit.LineNumberTag

data class CogniCryptDiagnostic(
    val message: String,
    val severity: DiagnosticSeverity,
    val location: Location,
    val highlightLocations: List<Location>)

class CryptoErrorReporter : PathConditionsErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    private fun tryGetSourceLocation(stmt: Unit): Location? {
        val positionTag = stmt.getTag("PositionTag") as? PositionTag
        if (positionTag != null)
            return positionTag.position.asLocation
        val lineNumberTag = stmt.getTag("LineNumberTag") as? LineNumberTag
        if (lineNumberTag != null) {
            val pos = Position(lineNumberTag.lineNumber - 1, 0)
            return Location(null, Range(pos, pos)) // TODO: specify URI
        }
        return null
    }

    override fun afterAnalysis() {
        diagnostics = this.errorMarkers.rowMap()
            .flatMap { klassMap ->
                klassMap.value.flatMap { methodMap ->
                    methodMap.value.map { error ->

                        val stmt = error.errorLocation.unit.get()
                        val location = (stmt.getTag("PositionTag") as PositionTag).position.asLocation
                        val msg = String.format("%s violating CrySL rule for %s. %s", error.javaClass.simpleName,
                            error.rule.className, error.toErrorMarkerString())

                        // TODO. get relatedInfo from crypto analysis.
                        val highlightPositions = when (error) {
                            is ErrorWithObjectAllocation -> error.dataFlowPath
                                .mapNotNull { tryGetSourceLocation(it.stmt().unit.get()) }
                                .toList()
                            else -> emptyList()
                        }

                        CogniCryptDiagnostic(msg, DiagnosticSeverity.Error, location, highlightPositions)
                    }
                }
            }
            .toList()
    }
}