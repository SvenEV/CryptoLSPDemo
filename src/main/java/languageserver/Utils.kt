package languageserver

import com.ibm.wala.cast.tree.CAstSourcePositionMap
import crypto.pathconditions.ofType
import de.upb.soot.frontends.java.DebuggingInformationTag
import de.upb.soot.frontends.java.PositionTag
import org.apache.commons.io.input.TeeInputStream
import org.apache.commons.io.output.TeeOutputStream
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import soot.SootMethod
import soot.Unit
import soot.tagkit.LineNumberTag
import java.io.*
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

//
// Source code position conversions (between WALA and LSP types)
//

/** Converts a WALA [CAstSourcePositionMap.Position] to an LSP [Range], losing the association to the source document */
val CAstSourcePositionMap.Position.asRange: Range
    get() {
        val start = Position(firstLine - 1, firstCol)
        val end = Position(lastLine - 1, lastCol)
        return Range(start, end)
    }

/** Converts a WALA [CAstSourcePositionMap.Position] to an LSP [Location] */
val CAstSourcePositionMap.Position.asLocation: Location get() = Location(this.url.toStringWithWindowsFix(), asRange)

/** Checks if a [Position] lies within a [Range] */
fun Range.contains(pos: Position) =
    pos.line >= start.line &&
        pos.line <= end.line &&
        pos.character >= start.character &&
        pos.character <= end.character

/**
 * Tries to determine the exact or approximate position of a statement in the source file
 * by looking for a [PositionTag] or a [LineNumberTag].
 */
fun tryGetSourceLocation(stmt: Unit): Location? {
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

/**
 * Tries to determine the exact position of a method in the source file by looking for a [DebuggingInformationTag].
 */
fun tryGetSourceLocation(method: SootMethod) =
    method.tags
        .ofType<DebuggingInformationTag>()
        .firstOrNull()?.debugInfo?.codeNamePosition?.asLocation


//
// File URI conversions
//

private fun fixFileUriOnWindows(uri: String) = when {
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


//
// Stream helpers
//

/** Wraps an [InputStream] so that it writes any read data to a temporary file.  */
fun InputStream.logStream(logFileName: String): InputStream = try {
    val log = File.createTempFile(logFileName, ".txt")
    TeeInputStream(this, FileOutputStream(log))
} catch (e: IOException) {
    this
}

/** Wraps an [OutputStream] so that it additionally writes to a temporary file.  */
fun OutputStream.logStream(logFileName: String): OutputStream = try {
    val log = File.createTempFile(logFileName, ".txt")
    TeeOutputStream(this, FileOutputStream(log))
} catch (e: IOException) {
    this
}
