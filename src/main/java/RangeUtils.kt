import com.ibm.wala.cast.tree.CAstSourcePositionMap
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URL

data class SourceRange(val range: Range, val serverUri: String)

/** Converts a WALA [CAstSourcePositionMap.Position] to an LSP [Range] */
val CAstSourcePositionMap.Position.asRange: Range
    get() {
        val start = Position(firstLine - 1, firstCol)
        val end = Position(lastLine - 1, lastCol)
        return Range(start, end)
    }

val CAstSourcePositionMap.Position.asSourceRange: SourceRange get() = SourceRange(asRange, this.url.toStringWithWindowsFix())

/** Checks if a [Position] lies within a [Range] */
fun Range.contains(pos: Position) =
    pos.line >= start.line &&
        pos.line <= end.line &&
        pos.character >= start.character &&
        pos.character <= end.character

fun fixFileUriOnWindows(uri: String) = when {
    System.getProperty("os.name").toLowerCase().indexOf("win") >= 0 && !uri.startsWith("file:///") ->
        // take care of uri in windows
        uri.replace("file://", "file:///")
    else ->
        uri
}

fun URL.toStringWithWindowsFix() = fixFileUriOnWindows(toString())