import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position
import com.ibm.wala.cast.tree.impl.LineNumberPosition
import com.ibm.wala.util.collections.Pair
import crypto.analysis.errors.ErrorWithObjectAllocation
import crypto.reporting.PathConditionsErrorMarkerListener
import de.upb.soot.frontends.java.PositionTag
import magpiebridge.core.AnalysisResult
import magpiebridge.core.Kind
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import soot.Unit
import soot.tagkit.LineNumberTag

class CogniCryptDiagnostic(val message: String, private val position: Position, val highlightLocations: List<Location>) : AnalysisResult {
    override fun repair() = ""
    override fun related() = emptyList<Pair<Position, String>>()
    override fun severity() = DiagnosticSeverity.Error
    override fun kind() = Kind.Diagnostic
    override fun position() = position
    override fun toString(useMarkdown: Boolean) = message
}

class CryptoErrorReporter : PathConditionsErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

    private fun tryGetSourcePosition(stmt: Unit): Position? {
        val positionTag = stmt.getTag("PositionTag") as? PositionTag
        if (positionTag != null)
            return positionTag.position
        val lineNumberTag = stmt.getTag("LineNumberTag") as? LineNumberTag
        if (lineNumberTag != null)
            return LineNumberPosition(null, null, lineNumberTag.lineNumber) // TODO: specify url and localFile
        return null
    }

    override fun afterAnalysis() {
        diagnostics = this.errorMarkers.rowMap()
            .flatMap { klassMap ->
                klassMap.value.flatMap { methodMap ->
                    methodMap.value.map { error ->

                        val stmt = error.errorLocation.unit.get()
                        val position = (stmt.getTag("PositionTag") as PositionTag).position
                        val msg = String.format("%s violating CrySL rule for %s. %s", error.javaClass.simpleName,
                            error.rule.className, error.toErrorMarkerString())

                        // TODO. get relatedInfo from crypto analysis.
                        val highlightPositions = when (error) {
                            is ErrorWithObjectAllocation -> error.dataFlowPath
                                .mapNotNull { tryGetSourcePosition(it.stmt().unit.get())?.asLocation }
                                .toList()
                            else -> emptyList()
                        }

                        CogniCryptDiagnostic(msg, position, highlightPositions)
                    }
                }
            }
            .toList()
    }
}