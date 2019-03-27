import com.ibm.wala.cast.tree.CAstSourcePositionMap
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

data class SourceRange(val range: Range, val sourcePath: Path)

/** Converts a WALA [CAstSourcePositionMap.Position] to an LSP [Range] */
val CAstSourcePositionMap.Position.asRange: Range
    get() {
        val start = Position(firstLine - 1, firstCol)
        val end = Position(lastLine - 1, lastCol)
        return Range(start, end)
    }

val CAstSourcePositionMap.Position.asSourceRange: SourceRange get() = SourceRange(asRange, this.url.asFilePath)

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

// We must replace "file://" with "file:///" (WALA uses "file://")
// and un-escape special characters like ':' (VS Code escapes ':')
fun URL.toStringWithWindowsFix() = fixFileUriOnWindows(toString())
val String.asFilePath: Path get() = Paths.get(URI(fixFileUriOnWindows(this)))
val URL.asFilePath: Path get() = toString().asFilePath