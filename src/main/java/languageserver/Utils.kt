package languageserver

import com.ibm.wala.cast.tree.CAstSourcePositionMap
import crypto.pathconditions.ofType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import magpiebridge.converter.DebuggingInformationTag
import magpiebridge.converter.PositionInfoTag
import magpiebridge.converter.PositionTag
import org.apache.commons.io.input.TeeInputStream
import org.apache.commons.io.output.TeeOutputStream
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import soot.SootMethod
import soot.Unit
import java.io.*
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.streams.asSequence

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

val Range.isValid get() =
    start.line >= 0 && start.character >= 0 &&
        end.line >= start.line && end.character >= 0

/**
 * Tries to determine the exact position of a statement in the source file
 * by looking for a [PositionTag] or a [PositionInfoTag].
 */
val Unit.sourceLocation get() =
    tags.ofType<PositionTag>().firstOrNull()?.position?.asLocation
        ?: tags.ofType<PositionInfoTag>().firstOrNull()?.positionInfo?.stmtPosition?.asLocation

/**
 * Tries to determine the exact position of a method in the source file by looking for a [DebuggingInformationTag].
 */
val SootMethod.sourceLocation get() = tags
    .ofType<DebuggingInformationTag>()
    .firstOrNull()?.debugInfo?.codeNamePosition?.asLocation

/**
 * Reads exactly the part of a file represented by the given [Location].
 * @param asSingleLine If true, ranges spanning multiple lines are merged into a single line
 */
fun readRangeFromFile(location: Location?, asSingleLine: Boolean = false): String? {
    if (location?.uri == null || !location.range.isValid)
        return null

    val numLines = location.range.end.line - location.range.start.line + 1

    val linesInRange = Files.lines(location.uri.asFilePath)
        .asSequence()
        .drop(location.range.start.line)
        .take(numLines)
        .mapIndexed { i, line ->
            when {
                numLines == 1 -> line.substring(location.range.start.character until location.range.end.character)
                i == 0 -> line.substring(location.range.start.character)
                i == numLines - 1 -> line.substring(0 until location.range.end.character)
                else -> line
            }
        }

    return if (asSingleLine)
        linesInRange.map { it.trim() }.joinToString("")
    else
        linesInRange.joinToString(System.lineSeparator())
}

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

fun InputStream.logStream(): InputStream = TeeInputStream(this, System.out)
fun OutputStream.logStream(): OutputStream = TeeOutputStream(this, System.out)

//
// Concurrency helpers
//

class FutureValue<T> : CompletableFuture<T>() {
    @Deprecated("Use getAsync() instead to prevent cancellation", ReplaceWith("getAsync()"))
    fun await(): Nothing {
        throw Exception("Use getAsync() instead to prevent cancellation")
    }
}

/** Awaits the result of a future without causing it to be cancelled in case the awaiting job gets cancelled */
suspend fun <T> CompletableFuture<T>.getAsync(): T = withContext(NonCancellable) { await() }
