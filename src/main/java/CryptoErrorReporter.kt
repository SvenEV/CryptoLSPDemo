import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position
import com.ibm.wala.util.collections.Pair
import crypto.analysis.errors.ConstraintError
import crypto.reporting.ErrorMarkerListener
import de.upb.soot.frontends.java.PositionTag
import magpiebridge.core.AnalysisResult
import magpiebridge.core.Kind
import org.eclipse.lsp4j.DiagnosticSeverity

class CogniCryptDiagnostic(val message: String, private val position: Position, val highlightPositions: List<Position>) : AnalysisResult {
    override fun repair() = ""
    override fun related() = emptyList<Pair<Position, String>>()
    override fun severity() = DiagnosticSeverity.Error
    override fun kind() = Kind.Diagnostic
    override fun position() = position
    override fun toString(useMarkdown: Boolean) = message
}

class CryptoErrorReporter : ErrorMarkerListener() {
    lateinit var diagnostics: Collection<CogniCryptDiagnostic>

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
                            is ConstraintError -> error.callSiteWithExtractedValue.`val`.dataFlowStatements
                                    .map { (it.unit.get().getTag("PositionTag") as PositionTag).position }
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